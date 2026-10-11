package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.api.dto.RateLimitCheckRequest;
import com.throttl.ratelimiter.config.RateLimiterProperties;
import com.throttl.ratelimiter.domain.FailureMode;
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

@Service
public class RateLimitEngine {

    static final String REDIS_UNAVAILABLE = "redis-unavailable";
    static final String CATALOG_UNAVAILABLE = "policy-catalog-unavailable";

    private static final Logger log = LoggerFactory.getLogger(RateLimitEngine.class);

    private final PolicyCatalog policyCatalog;
    private final RedisRateLimiter redisRateLimiter;
    private final LocalFallbackLimiter localFallback;
    private final RateLimitMetrics metrics;
    private final RateLimiterProperties properties;

    public RateLimitEngine(
            PolicyCatalog policyCatalog,
            RedisRateLimiter redisRateLimiter,
            LocalFallbackLimiter localFallback,
            RateLimitMetrics metrics,
            RateLimiterProperties properties
    ) {
        this.policyCatalog = policyCatalog;
        this.redisRateLimiter = redisRateLimiter;
        this.localFallback = localFallback;
        this.metrics = metrics;
        this.properties = properties;
    }

    private record Resolved(List<Policy> policies, List<String> keys) {
        boolean isEmpty() {
            return policies.isEmpty();
        }
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
            try {
                return assemble(resolved.policies(), redisRateLimiter.evaluateAll(resolved.policies(), resolved.keys()));
            } catch (RuntimeException ex) {
                return degrade(resolved, ex);
            }
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
                .flatMap(resolved -> resolved.isEmpty()
                        ? Mono.just(unlimited())
                        : redisRateLimiter.evaluateAllReactive(resolved.policies(), resolved.keys())
                                .map(outcomes -> assemble(resolved.policies(), outcomes))
                                .onErrorResume(RuntimeException.class, ex -> Mono.just(degrade(resolved, ex))))
                .onErrorResume(CatalogFailure.class, ex -> Mono.just(catalogUnavailable((RuntimeException) ex.getCause())))
                .doFinally(signal -> sample.stop(metrics.checkLatencyTimer()));
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

    private RateLimitDecision assemble(List<Policy> applicable, List<RateLimitDecision> outcomes) {
        List<PolicyEvaluation> evaluations = new ArrayList<>();
        long tightestRemaining = Long.MAX_VALUE;
        RateLimitDecision denied = null;

        for (int i = 0; i < applicable.size(); i++) {
            Policy policy = applicable.get(i);
            if (denied != null) {
                evaluations.add(new PolicyEvaluation(policy.id(), PolicyEvaluation.SKIPPED, 0, 0));
                continue;
            }
            RateLimitDecision decision = outcomes.get(i);
            tightestRemaining = Math.min(tightestRemaining, decision.remaining());
            if (!decision.allowed()) {
                evaluations.add(new PolicyEvaluation(
                        policy.id(), PolicyEvaluation.REJECTED, decision.remaining(), decision.retryAfterMs()));
                metrics.incrementRejected(policy.id());
                denied = RateLimitDecision.deny(decision.remaining(), decision.retryAfterMs(), policy.id());
            } else {
                evaluations.add(new PolicyEvaluation(
                        policy.id(), PolicyEvaluation.ALLOWED, decision.remaining(), 0));
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
     * Redis could not answer. Each matching policy decides by its own failure
     * mode: any FAIL_CLOSED policy denies; otherwise LOCAL_FALLBACK policies are
     * enforced in process (all-or-nothing) and FAIL_OPEN policies allow.
     */
    private RateLimitDecision degrade(Resolved resolved, RuntimeException ex) {
        if (!(ex instanceof RedisUnavailableException)) {
            log.debug("Redis check failed; applying per-policy failure modes", ex);
        }
        List<Policy> policies = resolved.policies();

        int closedAt = -1;
        for (int i = 0; i < policies.size() && closedAt < 0; i++) {
            if (failureMode(policies.get(i)) == FailureMode.FAIL_CLOSED) {
                closedAt = i;
            }
        }
        if (closedAt >= 0) {
            return failClosed(policies, closedAt);
        }

        List<Integer> localIndexes = new ArrayList<>();
        for (int i = 0; i < policies.size(); i++) {
            if (failureMode(policies.get(i)) == FailureMode.LOCAL_FALLBACK) {
                localIndexes.add(i);
            }
        }
        List<RateLimitDecision> local = localIndexes.isEmpty()
                ? List.of()
                : localFallback.tryConsumeAll(
                        localIndexes.stream().map(policies::get).toList(),
                        localIndexes.stream().map(resolved.keys()::get).toList());

        List<PolicyEvaluation> evaluations = new ArrayList<>(policies.size());
        long tightestRemaining = Long.MAX_VALUE;
        RateLimitDecision denied = null;
        for (int i = 0; i < policies.size(); i++) {
            Policy policy = policies.get(i);
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
            metrics.incrementRejected(denied.rejectedBy());
            return denied.withEvaluations(evaluations).asDegraded();
        }
        metrics.incrementAllowed();
        long remaining = tightestRemaining == Long.MAX_VALUE ? 0 : tightestRemaining;
        return RateLimitDecision.allow(remaining).withEvaluations(evaluations).asDegraded();
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

    private static final class CatalogFailure extends RuntimeException {
        CatalogFailure(Throwable cause) {
            super(cause);
        }
    }
}
