package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.api.dto.RateLimitCheckRequest;
import com.throttl.ratelimiter.config.RateLimiterProperties;
import com.throttl.ratelimiter.domain.Algorithm;
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

    private static final Logger log = LoggerFactory.getLogger(RateLimitEngine.class);

    private final PolicyCatalog policyCatalog;
    private final TokenBucketAlgorithm tokenBucketAlgorithm;
    private final RateLimitMetrics metrics;
    private final RateLimiterProperties properties;

    public RateLimitEngine(
            PolicyCatalog policyCatalog,
            TokenBucketAlgorithm tokenBucketAlgorithm,
            RateLimitMetrics metrics,
            RateLimiterProperties properties
    ) {
        this.policyCatalog = policyCatalog;
        this.tokenBucketAlgorithm = tokenBucketAlgorithm;
        this.metrics = metrics;
        this.properties = properties;
    }

    public RateLimitDecision check(RateLimitCheckRequest request) {
        Timer.Sample sample = Timer.start();
        metrics.incrementChecks();
        try {
            return evaluateCatalog(policyCatalog.enabledPolicies(), request);
        } catch (RuntimeException ex) {
            return failClosedOrThrow(ex);
        } finally {
            sample.stop(metrics.checkLatencyTimer());
        }
    }

    public Mono<RateLimitDecision> checkReactive(RateLimitCheckRequest request) {
        Timer.Sample sample = Timer.start();
        metrics.incrementChecks();
        return policyCatalog.enabledPoliciesAsync()
                .flatMap(catalog -> evaluateCatalogReactive(catalog, request))
                .onErrorResume(RuntimeException.class, ex -> Mono.just(failClosedOrThrow(ex)))
                .doFinally(signal -> sample.stop(metrics.checkLatencyTimer()));
    }

    private RateLimitDecision evaluateCatalog(List<Policy> catalog, RateLimitCheckRequest request) {
        Timer.Sample resolveSample = Timer.start();
        List<Policy> applicable = PolicyResolver.resolve(catalog, request);
        resolveSample.stop(metrics.policyResolutionTimer());
        if (applicable.isEmpty()) {
            metrics.incrementAllowed();
            return RateLimitDecision.allow(Long.MAX_VALUE);
        }

        List<String> bucketKeys = new ArrayList<>(applicable.size());
        for (Policy policy : applicable) {
            algorithmFor(policy);
            bucketKeys.add(PolicyResolver.bucketKey(policy, request));
        }
        List<RateLimitDecision> outcomes = tokenBucketAlgorithm.evaluateAll(applicable, bucketKeys);
        return assemble(applicable, outcomes);
    }

    private Mono<RateLimitDecision> evaluateCatalogReactive(List<Policy> catalog, RateLimitCheckRequest request) {
        Timer.Sample resolveSample = Timer.start();
        List<Policy> applicable = PolicyResolver.resolve(catalog, request);
        resolveSample.stop(metrics.policyResolutionTimer());
        if (applicable.isEmpty()) {
            metrics.incrementAllowed();
            return Mono.just(RateLimitDecision.allow(Long.MAX_VALUE));
        }
        List<String> bucketKeys = new ArrayList<>(applicable.size());
        for (Policy policy : applicable) {
            algorithmFor(policy);
            bucketKeys.add(PolicyResolver.bucketKey(policy, request));
        }
        return tokenBucketAlgorithm.evaluateAllReactive(applicable, bucketKeys)
                .map(outcomes -> assemble(applicable, outcomes));
    }

    private RateLimitDecision assemble(List<Policy> applicable, List<RateLimitDecision> outcomes) {
        List<PolicyEvaluation> evaluations = new ArrayList<>();
        long tightestRemaining = Long.MAX_VALUE;
        boolean rejected = false;
        RateLimitDecision denied = null;

        for (int i = 0; i < applicable.size(); i++) {
            Policy policy = applicable.get(i);
            if (rejected) {
                evaluations.add(new PolicyEvaluation(policy.id(), PolicyEvaluation.SKIPPED, 0, 0));
                continue;
            }
            RateLimitDecision decision = outcomes.get(i);
            tightestRemaining = Math.min(tightestRemaining, decision.remaining());
            if (!decision.allowed()) {
                evaluations.add(new PolicyEvaluation(
                        policy.id(),
                        PolicyEvaluation.REJECTED,
                        decision.remaining(),
                        decision.retryAfterMs()
                ));
                metrics.incrementRejected(policy.id());
                rejected = true;
                denied = RateLimitDecision.deny(decision.remaining(), decision.retryAfterMs(), policy.id())
                        .withEvaluations(evaluations);
            } else {
                evaluations.add(new PolicyEvaluation(
                        policy.id(),
                        PolicyEvaluation.ALLOWED,
                        decision.remaining(),
                        0
                ));
            }
        }

        if (denied != null) {
            return denied.withEvaluations(evaluations);
        }
        metrics.incrementAllowed();
        long remaining = tightestRemaining == Long.MAX_VALUE ? 0 : tightestRemaining;
        return RateLimitDecision.allow(remaining).withEvaluations(evaluations);
    }

    private RateLimitDecision failClosedOrThrow(RuntimeException ex) {
        log.warn("Rate limit check failed; fail-closed={}", properties.failClosed(), ex);
        if (properties.failClosed()) {
            metrics.incrementRejected("redis-unavailable");
            return RateLimitDecision.deny(0, 0, "redis-unavailable");
        }
        throw ex;
    }

    private RateLimitAlgorithm algorithmFor(Policy policy) {
        if (policy.strategy().algorithm() == Algorithm.TOKEN_BUCKET) {
            return tokenBucketAlgorithm;
        }
        throw new IllegalStateException("Unsupported algorithm: " + policy.strategy().algorithm());
    }
}
