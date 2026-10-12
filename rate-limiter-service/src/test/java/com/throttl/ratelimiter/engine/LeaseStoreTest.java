package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.metrics.RateLimitMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LeaseStoreTest {

    private static final String KEY = "rl:{USER:u1}:p";

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LeaseStore store = new LeaseStore(nanos::get, new RateLimitMetrics(registry));

    @Test
    void spendsExactlyTheGrantedUnits() {
        store.add(KEY, 3, 1_000);

        assertThat(store.tryTake(KEY)).isTrue();
        assertThat(store.tryTake(KEY)).isTrue();
        assertThat(store.tryTake(KEY)).isTrue();
        assertThat(store.tryTake(KEY)).isFalse();
    }

    @Test
    void noLeaseMeansGoToRedis() {
        assertThat(store.tryTake(KEY)).isFalse();
    }

    @Test
    void expiredUnitsCannotBeSpent() {
        store.add(KEY, 5, 100);
        advanceMillis(100);

        assertThat(store.tryTake(KEY)).isFalse();
    }

    @Test
    void refillCarriesLiveUnitsAndDropsExpiredOnes() {
        store.add(KEY, 2, 100);
        store.add(KEY, 3, 100);
        assertThat(store.remaining(KEY)).isEqualTo(5);

        advanceMillis(150);
        store.add(KEY, 4, 100);
        assertThat(store.remaining(KEY)).as("expired units are not carried").isEqualTo(4);
        assertThat(registry.counter("rate_limit_lease_units_expired_total").count()).isEqualTo(5);
    }

    @Test
    void giveBackReturnsAUnitToALiveLease() {
        store.add(KEY, 1, 1_000);
        assertThat(store.tryTake(KEY)).isTrue();

        store.giveBack(KEY);

        assertThat(store.tryTake(KEY)).isTrue();
    }

    @Test
    void concurrentSpendersNeverExceedTheGrant() throws Exception {
        AtomicInteger taken = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(16)) {
            for (int round = 0; round < 20; round++) {
                executor.submit(() -> store.add(KEY, 50, 60_000));
                for (int i = 0; i < 100; i++) {
                    executor.submit(() -> {
                        if (store.tryTake(KEY)) {
                            taken.incrementAndGet();
                        }
                    });
                }
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(taken.get() + store.remaining(KEY))
                .as("every granted unit is either spent once or still held")
                .isEqualTo(20 * 50);
    }

    private void advanceMillis(long millis) {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }
}
