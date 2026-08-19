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
            List<Policy> applicable = PolicyResolver.resolve(policyCatalog.enabledPolicies(), request);
            if (applicable.isEmpty()) {
                RateLimitDecision decision = RateLimitDecision.allow(Long.MAX_VALUE);
                metrics.incrementAllowed();
                return decision;
            }

            long tightestRemaining = Long.MAX_VALUE;
            for (Policy policy : applicable) {
                RateLimitAlgorithm algorithm = algorithmFor(policy);
                String bucketKey = PolicyResolver.bucketKey(policy, request);
                RateLimitDecision decision = algorithm.evaluate(policy, bucketKey);
                tightestRemaining = Math.min(tightestRemaining, decision.remaining());
                if (!decision.allowed()) {
                    metrics.incrementRejected(policy.id());
                    return RateLimitDecision.deny(decision.remaining(), decision.retryAfterMs(), policy.id());
                }
            }
            metrics.incrementAllowed();
            return RateLimitDecision.allow(tightestRemaining == Long.MAX_VALUE ? 0 : tightestRemaining);
        } catch (RuntimeException ex) {
            log.warn("Rate limit check failed; fail-closed={}", properties.failClosed(), ex);
            if (properties.failClosed()) {
                metrics.incrementRejected("redis-unavailable");
                return RateLimitDecision.deny(0, 0, "redis-unavailable");
            }
            throw ex;
        } finally {
            sample.stop(metrics.checkLatencyTimer());
        }
    }

    private RateLimitAlgorithm algorithmFor(Policy policy) {
        if (policy.strategy().algorithm() == Algorithm.TOKEN_BUCKET) {
            return tokenBucketAlgorithm;
        }
        throw new IllegalStateException("Unsupported algorithm: " + policy.strategy().algorithm());
    }
}
