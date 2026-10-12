package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.api.dto.RateLimitCheckRequest;
import com.throttl.ratelimiter.config.RateLimiterProperties;
import com.throttl.ratelimiter.domain.FailureMode;
import com.throttl.ratelimiter.domain.LeaseConfiguration;
import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.metrics.RateLimitMetrics;
import com.throttl.ratelimiter.resolver.PolicyResolver;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides a check in up to three steps, cheapest first:
 * <ol>
 *   <li>a bucket Redis just denied is denied locally until its retryAfter;</li>
 *   <li>leased policies spend units this instance already took from Redis;</li>
 *   <li>everything else goes to Redis in one atomic script (lease refills ride
 *       along in the same call).</li>
 * </ol>
 * If Redis cannot answer, each remaining policy's failure mode decides.
 */
@Service
public class RateLimitEngine {

    static final String REDIS_UNAVAILABLE = "redis-unavailable";
    static final String CATALOG_UNAVAILABLE = "policy-catalog-unavailable";

    private static final Logger log = LoggerFactory.getLogger(RateLimitEngine.class);

    private final PolicyCatalog policyCatalog;
    private final RedisRateLimiter redisRateLimiter;
    private final LocalFallbackLimiter localFallback;
    private final LocalDenyCache denyCache;
    private final LeaseStore leases;
    private final RateLimitMetrics metrics;
    private final RateLimiterProperties properties;

    public RateLimitEngine(
            PolicyCatalog policyCatalog,
            RedisRateLimiter redisRateLimiter,
            LocalFallbackLimiter localFallback,
            LocalDenyCache denyCache,
            LeaseStore leases,
            RateLimitMetrics metrics,
            RateLimiterProperties properties
    ) {
        this.policyCatalog = policyCatalog;
        this.redisRateLimiter = redisRateLimiter;
        this.localFallback = localFallback;
        this.denyCache = denyCache;
        this.leases = leases;
        this.metrics = metrics;
        this.properties = properties;
        policyCatalog.addChangeListener(this::clearLocalState);
    }

    private record Resolved(List<Policy> policies, List<String> keys) {
        boolean isEmpty() {
            return policies.isEmpty();
        }
    }

    /**
     * @param leased  policies satisfied by a unit this instance already holds
     * @param remote  indexes that must be decided in Redis
     * @param wants   units to request per remote index (lease size for a refill)
     */
    private record Plan(boolean[] leased, List<Integer> remote, List<Long> wants) {
    }

    public RateLimitDecision check(RateLimitCheckRequest request) {
        Timer.Sample sample = Timer.start();
        metrics.incrementChecks();
        try {
            List<Policy> catalog;
            try {
                catalog = policyCatalog.enabledPolicies();
            } catch (RuntimeException ex) {
                return catalogUnavailable(ex);
            }
            Resolved resolved = resolve(catalog, request);
            if (resolved.isEmpty()) {
                return unlimited();
            }
            RateLimitDecision cached = deniedLocally(resolved);
            if (cached != null) {
                return cached;
            }
            Plan plan = plan(resolved);
            if (plan.remote().isEmpty()) {
                return finish(resolved, plan, List.of());
            }
            List<BucketOutcome> outcomes;
            try {
                outcomes = redisRateLimiter.evaluateAll(
                        pick(resolved.policies(), plan.remote()), pick(resolved.keys(), plan.remote()), plan.wants());
            } catch (RuntimeException ex) {
                return degrade(resolved, plan, ex);
            }
            return finish(resolved, plan, outcomes);
        } finally {
            sample.stop(metrics.checkLatencyTimer());
        }
    }

    public Mono<RateLimitDecision> checkReactive(RateLimitCheckRequest request) {
        Timer.Sample sample = Timer.start();
        metrics.incrementChecks();
        return policyCatalog.enabledPoliciesAsync()
                .map(catalog -> resolve(catalog, request))
                .onErrorMap(RuntimeException.class, CatalogFailure::new)
                .flatMap(this::decideReactive)
                .onErrorResume(CatalogFailure.class, ex -> Mono.just(catalogUnavailable((RuntimeException) ex.getCause())))
                .doFinally(signal -> sample.stop(metrics.checkLatencyTimer()));
    }

    private Mono<RateLimitDecision> decideReactive(Resolved resolved) {
        if (resolved.isEmpty()) {
            return Mono.just(unlimited());
        }
        RateLimitDecision cached = deniedLocally(resolved);
        if (cached != null) {
            return Mono.just(cached);
        }
        Plan plan = plan(resolved);
        if (plan.remote().isEmpty()) {
            return Mono.just(finish(resolved, plan, List.of()));
        }
        return redisRateLimiter.evaluateAllReactive(
                        pick(resolved.policies(), plan.remote()), pick(resolved.keys(), plan.remote()), plan.wants())
                .map(outcomes -> finish(resolved, plan, outcomes))
                .onErrorResume(RuntimeException.class, ex -> Mono.just(degrade(resolved, plan, ex)));
    }

    private Resolved resolve(List<Policy> catalog, RateLimitCheckRequest request) {
        Timer.Sample resolveSample = Timer.start();
        List<Policy> applicable = PolicyResolver.resolve(catalog, request);
        resolveSample.stop(metrics.policyResolutionTimer());
        List<String> keys = new ArrayList<>(applicable.size());
        for (Policy policy : applicable) {
            keys.add(PolicyResolver.bucketKey(policy, request));
        }
        return new Resolved(applicable, keys);
    }

    private RateLimitDecision unlimited() {
        metrics.incrementAllowed();
        return RateLimitDecision.allow(Long.MAX_VALUE);
    }

    private RateLimitDecision deniedLocally(Resolved resolved) {
        for (int i = 0; i < resolved.policies().size(); i++) {
            long retryAfterMs = denyCache.retryAfterMs(resolved.keys().get(i));
            if (retryAfterMs <= 0) {
                continue;
            }
            Policy denier = resolved.policies().get(i);
            List<PolicyEvaluation> evaluations = new ArrayList<>(resolved.policies().size());
            for (Policy policy : resolved.policies()) {
                evaluations.add(policy == denier
                        ? new PolicyEvaluation(policy.id(), PolicyEvaluation.REJECTED, 0, retryAfterMs)
                        : new PolicyEvaluation(policy.id(), PolicyEvaluation.SKIPPED, 0, 0));
            }
            metrics.incrementLocalDecision("deny_cache");
            metrics.incrementRejected(denier.id());
            return RateLimitDecision.deny(0, retryAfterMs, denier.id()).withEvaluations(evaluations);
        }
        return null;
    }

    private Plan plan(Resolved resolved) {
        int size = resolved.policies().size();
        boolean[] leased = new boolean[size];
        List<Integer> remote = new ArrayList<>(size);
        List<Long> wants = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            LeaseConfiguration lease = resolved.policies().get(i).strategy().lease();
            if (lease != null && leases.tryTake(resolved.keys().get(i))) {
                leased[i] = true;
            } else {
                remote.add(i);
                wants.add(lease == null ? 1L : lease.tokens());
            }
        }
        return new Plan(leased, remote, wants);
    }

    private RateLimitDecision finish(Resolved resolved, Plan plan, List<BucketOutcome> remoteOutcomes) {
        List<Policy> policies = resolved.policies();
        List<String> keys = resolved.keys();
        BucketOutcome[] outcomes = new BucketOutcome[policies.size()];
        for (int i = 0; i < policies.size(); i++) {
            if (plan.leased()[i]) {
                outcomes[i] = new BucketOutcome(true, leases.remaining(keys.get(i)), 0, 1);
            }
        }
        for (int j = 0; j < plan.remote().size(); j++) {
            outcomes[plan.remote().get(j)] = remoteOutcomes.get(j);
        }

        boolean allAllowed = true;
        for (BucketOutcome outcome : outcomes) {
            allAllowed &= outcome.allowed();
        }
        for (int i = 0; i < policies.size(); i++) {
            String key = keys.get(i);
            if (plan.leased()[i]) {
                if (allAllowed) {
                    metrics.incrementLocalDecision("lease");
                } else {
                    leases.giveBack(key);
                }
                continue;
            }
            LeaseConfiguration lease = policies.get(i).strategy().lease();
            BucketOutcome outcome = outcomes[i];
            if (allAllowed && lease != null && outcome.granted() > 1) {
                leases.add(key, outcome.granted() - 1, lease.ttlMs());
            } else if (!outcome.allowed()) {
                denyCache.deny(key, outcome.retryAfterMs());
            }
        }
        return assemble(policies, outcomes, plan.leased());
    }

    private RateLimitDecision assemble(List<Policy> policies, BucketOutcome[] outcomes, boolean[] leased) {
        List<PolicyEvaluation> evaluations = new ArrayList<>(policies.size());
        long tightestRemaining = Long.MAX_VALUE;
        RateLimitDecision denied = null;

        for (int i = 0; i < policies.size(); i++) {
            Policy policy = policies.get(i);
            if (denied != null) {
                evaluations.add(new PolicyEvaluation(policy.id(), PolicyEvaluation.SKIPPED, 0, 0));
                continue;
            }
            BucketOutcome outcome = outcomes[i];
            tightestRemaining = Math.min(tightestRemaining, outcome.remaining());
            if (!outcome.allowed()) {
                evaluations.add(new PolicyEvaluation(
                        policy.id(), PolicyEvaluation.REJECTED, outcome.remaining(), outcome.retryAfterMs()));
                metrics.incrementRejected(policy.id());
                denied = RateLimitDecision.deny(outcome.remaining(), outcome.retryAfterMs(), policy.id());
            } else {
                String status = leased[i] ? PolicyEvaluation.LEASED : PolicyEvaluation.ALLOWED;
                evaluations.add(new PolicyEvaluation(policy.id(), status, outcome.remaining(), 0));
            }
        }

        if (denied != null) {
            return denied.withEvaluations(evaluations);
        }
        metrics.incrementAllowed();
        long remaining = tightestRemaining == Long.MAX_VALUE ? 0 : tightestRemaining;
        return RateLimitDecision.allow(remaining).withEvaluations(evaluations);
    }

    /**
     * Redis could not answer. Units already leased are prepaid and still count.
     * Each other policy decides by its own failure mode: any FAIL_CLOSED policy
     * denies; otherwise LOCAL_FALLBACK policies are enforced in process
     * (all-or-nothing) and FAIL_OPEN policies allow.
     */
    private RateLimitDecision degrade(Resolved resolved, Plan plan, RuntimeException ex) {
        if (!(ex instanceof RedisUnavailableException)) {
            log.debug("Redis check failed; applying per-policy failure modes", ex);
        }
        List<Policy> policies = resolved.policies();
        List<String> keys = resolved.keys();

        for (int i : plan.remote()) {
            if (failureMode(policies.get(i)) == FailureMode.FAIL_CLOSED) {
                giveBackLeased(resolved, plan);
                return failClosed(policies, i);
            }
        }

        List<Integer> localIndexes = plan.remote().stream()
                .filter(i -> failureMode(policies.get(i)) == FailureMode.LOCAL_FALLBACK)
                .toList();
        List<RateLimitDecision> local = localIndexes.isEmpty()
                ? List.of()
                : localFallback.tryConsumeAll(pick(policies, localIndexes), pick(keys, localIndexes));

        List<PolicyEvaluation> evaluations = new ArrayList<>(policies.size());
        long tightestRemaining = Long.MAX_VALUE;
        RateLimitDecision denied = null;
        for (int i = 0; i < policies.size(); i++) {
            Policy policy = policies.get(i);
            if (plan.leased()[i]) {
                long remaining = leases.remaining(keys.get(i));
                tightestRemaining = Math.min(tightestRemaining, remaining);
                evaluations.add(new PolicyEvaluation(policy.id(), PolicyEvaluation.LEASED, remaining, 0));
                continue;
            }
            int localIndex = localIndexes.indexOf(i);
            if (localIndex < 0) {
                evaluations.add(new PolicyEvaluation(policy.id(), PolicyEvaluation.FAILED_OPEN, 0, 0));
                metrics.incrementDegraded(policy.id(), FailureMode.FAIL_OPEN.name(), true);
                continue;
            }
            RateLimitDecision decision = local.get(localIndex);
            metrics.incrementDegraded(policy.id(), FailureMode.LOCAL_FALLBACK.name(), decision.allowed());
            if (denied != null) {
                evaluations.add(new PolicyEvaluation(policy.id(), PolicyEvaluation.SKIPPED, 0, 0));
            } else if (decision.allowed()) {
                tightestRemaining = Math.min(tightestRemaining, decision.remaining());
                evaluations.add(new PolicyEvaluation(
                        policy.id(), PolicyEvaluation.LOCAL_ALLOWED, decision.remaining(), 0));
            } else {
                evaluations.add(new PolicyEvaluation(
                        policy.id(), PolicyEvaluation.LOCAL_REJECTED, decision.remaining(), decision.retryAfterMs()));
                denied = RateLimitDecision.deny(decision.remaining(), decision.retryAfterMs(), policy.id());
            }
        }
        if (denied != null) {
            giveBackLeased(resolved, plan);
            metrics.incrementRejected(denied.rejectedBy());
            return denied.withEvaluations(evaluations).asDegraded();
        }
        metrics.incrementAllowed();
        long remaining = tightestRemaining == Long.MAX_VALUE ? 0 : tightestRemaining;
        return RateLimitDecision.allow(remaining).withEvaluations(evaluations).asDegraded();
    }

    private void giveBackLeased(Resolved resolved, Plan plan) {
        for (int i = 0; i < plan.leased().length; i++) {
            if (plan.leased()[i]) {
                leases.giveBack(resolved.keys().get(i));
            }
        }
    }

    private RateLimitDecision failClosed(List<Policy> policies, int closedAt) {
        List<PolicyEvaluation> evaluations = new ArrayList<>(policies.size());
        for (int i = 0; i < policies.size(); i++) {
            Policy policy = policies.get(i);
            String status = i == closedAt ? PolicyEvaluation.FAILED_CLOSED : PolicyEvaluation.SKIPPED;
            evaluations.add(new PolicyEvaluation(policy.id(), status, 0, 0));
        }
        metrics.incrementDegraded(policies.get(closedAt).id(), FailureMode.FAIL_CLOSED.name(), false);
        metrics.incrementRejected(REDIS_UNAVAILABLE);
        return RateLimitDecision.deny(0, 0, REDIS_UNAVAILABLE).withEvaluations(evaluations).asDegraded();
    }

    FailureMode failureMode(Policy policy) {
        FailureMode explicit = policy.metadata().failureMode();
        if (explicit != null) {
            return explicit;
        }
        return properties.failClosed() ? FailureMode.FAIL_CLOSED : FailureMode.FAIL_OPEN;
    }

    /** Policies themselves could not be loaded, so no per-policy mode is known. */
    private RateLimitDecision catalogUnavailable(RuntimeException ex) {
        log.warn("Policy catalog unavailable; applying service default fail-closed={}", properties.failClosed(), ex);
        if (properties.failClosed()) {
            metrics.incrementRejected(CATALOG_UNAVAILABLE);
            return RateLimitDecision.deny(0, 0, CATALOG_UNAVAILABLE).asDegraded();
        }
        metrics.incrementAllowed();
        return RateLimitDecision.allow(0).asDegraded();
    }

    private void clearLocalState() {
        denyCache.clear();
        leases.clear();
        localFallback.clear();
    }

    private static <T> List<T> pick(List<T> items, List<Integer> indexes) {
        if (indexes.size() == items.size()) {
            return items;
        }
        return indexes.stream().map(items::get).toList();
    }

    private static final class CatalogFailure extends RuntimeException {
        CatalogFailure(Throwable cause) {
            super(cause);
        }
    }
}
