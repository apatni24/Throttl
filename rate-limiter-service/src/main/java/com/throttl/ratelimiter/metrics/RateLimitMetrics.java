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
    private final Counter crossSlotRefunds;
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
        this.crossSlotRefunds = Counter.builder("rate_limit_cross_slot_refunds_total")
                .description("Slot groups refunded because another slot group denied the request")
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

    public void incrementCrossSlotRefunds() {
        crossSlotRefunds.increment();
    }

    public void incrementRedisShortCircuited() {
        Counter.builder("redis_short_circuited_total")
                .description("Redis calls skipped because the circuit breaker was open")
                .register(registry)
                .increment();
    }

    public void incrementBreakerOpened() {
        Counter.builder("redis_circuit_opened_total")
                .description("Times the Redis circuit breaker opened")
                .register(registry)
                .increment();
    }

    public void registerBreakerState(java.util.function.BooleanSupplier open) {
        io.micrometer.core.instrument.Gauge.builder("redis_circuit_open", () -> open.getAsBoolean() ? 1 : 0)
                .description("1 while Redis calls are being skipped")
                .register(registry);
    }

    /** One per policy evaluated while Redis was unavailable. */
    public void incrementDegraded(String policyId, String mode, boolean allowed) {
        Counter.builder("rate_limit_degraded_total")
                .description("Policy evaluations decided without Redis, by failure mode")
                .tag("policy", policyId)
                .tag("mode", mode)
                .tag("outcome", allowed ? "allowed" : "rejected")
                .register(registry)
                .increment();
    }

    /** A policy decision made in process instead of in Redis. */
    public void incrementLocalDecision(String source) {
        Counter.builder("rate_limit_local_decisions_total")
                .description("Policy evaluations answered without a Redis round trip")
                .tag("source", source)
                .register(registry)
                .increment();
    }

    public void incrementLeaseUnitsExpired(long units) {
        Counter.builder("rate_limit_lease_units_expired_total")
                .description("Leased units that expired unspent (bounded under-admission)")
                .register(registry)
                .increment(units);
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
