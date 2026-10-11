package com.throttl.ratelimiter.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class RateLimitMetrics {

    private final MeterRegistry registry;
    private final Counter checks;
    private final Counter allowed;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter redisErrors;
    private final Timer checkLatency;
    private final Timer redisOperation;
    private final Timer generationGet;
    private final Timer policyResolution;

    public RateLimitMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.checks = Counter.builder("rate_limit_checks_total")
                .description("Total rate-limit checks")
                .register(registry);
        this.allowed = Counter.builder("rate_limit_allowed_total")
                .description("Allowed rate-limit checks")
                .register(registry);
        this.cacheHits = Counter.builder("policy_cache_hits_total")
                .description("Policy cache hits")
                .register(registry);
        this.cacheMisses = Counter.builder("policy_cache_misses_total")
                .description("Policy cache misses")
                .register(registry);
        this.redisErrors = Counter.builder("redis_errors_total")
                .description("Redis operation errors")
                .register(registry);
        this.checkLatency = Timer.builder("rate_limit_check_latency_seconds")
                .description("End-to-end check latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
        this.redisOperation = Timer.builder("redis_operation_latency_seconds")
                .description("Token-bucket Redis EVAL/EVALSHA latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
        this.generationGet = Timer.builder("policy_generation_get_latency_seconds")
                .description("Redis GET of policy:generation")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
        this.policyResolution = Timer.builder("policy_resolution_latency_seconds")
                .description("In-memory policy matching after catalog load")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }

    public void incrementChecks() {
        checks.increment();
    }

    public void incrementAllowed() {
        allowed.increment();
    }

    public void incrementRejected(String policyId) {
        Counter.builder("rate_limit_rejected_total")
                .description("Rejected rate-limit checks")
                .tag("policy", policyId == null ? "unknown" : policyId)
                .register(registry)
                .increment();
    }

    public void incrementCacheHits() {
        cacheHits.increment();
    }

    public void incrementCacheMisses() {
        cacheMisses.increment();
    }

    public void incrementRedisErrors() {
        redisErrors.increment();
    }

    public Timer checkLatencyTimer() {
        return checkLatency;
    }

    public Timer redisOperationTimer() {
        return redisOperation;
    }

    public Timer generationGetTimer() {
        return generationGet;
    }

    public Timer policyResolutionTimer() {
        return policyResolution;
    }
}
