package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.metrics.RateLimitMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RedisCircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(10_000);
    private final RedisCircuitBreaker breaker =
            new RedisCircuitBreaker(1_000, now::get, new RateLimitMetrics(new SimpleMeterRegistry()));

    @Test
    void closedBreakerLetsEveryCallThrough() {
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void failureSkipsRedisUntilCoolDownEnds() {
        breaker.onFailure(new RuntimeException("timeout"));

        now.addAndGet(999);
        assertThat(breaker.allowRequest()).isFalse();
        assertThat(breaker.isOpen()).isTrue();
    }

    @Test
    void afterCoolDownExactlyOneProbeGoesThrough() {
        breaker.onFailure(new RuntimeException("timeout"));
        now.addAndGet(1_000);

        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.allowRequest()).as("second caller waits for the probe").isFalse();
    }

    @Test
    void successfulProbeCloses() {
        breaker.onFailure(new RuntimeException("timeout"));
        now.addAndGet(1_000);
        breaker.allowRequest();

        breaker.onSuccess();

        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    void failedProbeReopensForAnotherCoolDown() {
        breaker.onFailure(new RuntimeException("timeout"));
        now.addAndGet(1_000);
        breaker.allowRequest();

        breaker.onFailure(new RuntimeException("still down"));

        now.addAndGet(500);
        assertThat(breaker.allowRequest()).isFalse();
        now.addAndGet(500);
        assertThat(breaker.allowRequest()).isTrue();
    }
}
