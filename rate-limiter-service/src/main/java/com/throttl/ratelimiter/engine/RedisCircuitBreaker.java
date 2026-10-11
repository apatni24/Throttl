package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.config.RateLimiterProperties;
import com.throttl.ratelimiter.metrics.RateLimitMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Stops every check from paying the Redis timeout during an outage. After a
 * failure, Redis calls are skipped for {@code throttl.redis-breaker-open-ms};
 * then one probe is let through. The probe's result closes or re-opens it.
 */
@Component
public class RedisCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(RedisCircuitBreaker.class);
    private static final long CLOSED = 0L;

    private final long openMillis;
    private final LongSupplier clock;
    private final RateLimitMetrics metrics;
    private final AtomicLong openUntil = new AtomicLong(CLOSED);
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);

    @Autowired
    public RedisCircuitBreaker(RateLimiterProperties properties, RateLimitMetrics metrics) {
        this(properties.redisBreakerOpenMs(), System::currentTimeMillis, metrics);
    }

    RedisCircuitBreaker(long openMillis, LongSupplier clock, RateLimitMetrics metrics) {
        this.openMillis = openMillis;
        this.clock = clock;
        this.metrics = metrics;
        metrics.registerBreakerState(this::isOpen);
    }

    /** Whether the caller should attempt Redis. False means degrade immediately. */
    public boolean allowRequest() {
        long until = openUntil.get();
        if (until == CLOSED) {
            return true;
        }
        if (clock.getAsLong() < until) {
            return false;
        }
        return probeInFlight.compareAndSet(false, true);
    }

    public void onSuccess() {
        if (openUntil.getAndSet(CLOSED) != CLOSED) {
            probeInFlight.set(false);
            log.info("Redis reachable again; circuit closed");
        }
    }

    public void onFailure(Throwable error) {
        long previous = openUntil.getAndSet(clock.getAsLong() + openMillis);
        probeInFlight.set(false);
        if (previous == CLOSED) {
            metrics.incrementBreakerOpened();
            log.warn("Redis call failed; circuit open for {} ms: {}", openMillis, error.toString());
        }
    }

    public boolean isOpen() {
        return openUntil.get() != CLOSED;
    }
}
