package com.throttl.ratelimiter.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDenyCacheTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final LocalDenyCache cache = new LocalDenyCache(true, 1_000, nanos::get);

    @Test
    void deniesUntilRetryAfterThenForgets() {
        cache.deny("rl:{USER:u1}:p", 300);

        advanceMillis(299);
        assertThat(cache.retryAfterMs("rl:{USER:u1}:p")).isEqualTo(1);
        advanceMillis(1);
        assertThat(cache.retryAfterMs("rl:{USER:u1}:p")).isZero();
    }

    @Test
    void trustsADenialForAtMostTheCap() {
        cache.deny("rl:{USER:u1}:p", 3_600_000);

        assertThat(cache.retryAfterMs("rl:{USER:u1}:p")).isEqualTo(1_000);
        advanceMillis(1_000);
        assertThat(cache.retryAfterMs("rl:{USER:u1}:p")).isZero();
    }

    @Test
    void otherKeysAreUnaffected() {
        cache.deny("rl:{USER:u1}:p", 500);
        assertThat(cache.retryAfterMs("rl:{USER:u2}:p")).isZero();
    }

    @Test
    void clearDropsEverything() {
        cache.deny("rl:{USER:u1}:p", 500);
        cache.clear();
        assertThat(cache.retryAfterMs("rl:{USER:u1}:p")).isZero();
    }

    @Test
    void disabledCacheNeverDenies() {
        LocalDenyCache disabled = new LocalDenyCache(false, 1_000, nanos::get);
        disabled.deny("rl:{USER:u1}:p", 500);
        assertThat(disabled.retryAfterMs("rl:{USER:u1}:p")).isZero();
    }

    private void advanceMillis(long millis) {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }
}
