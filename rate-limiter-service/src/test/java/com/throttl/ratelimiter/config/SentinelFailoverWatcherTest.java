package com.throttl.ratelimiter.config;

import com.throttl.ratelimiter.metrics.RateLimitMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SentinelFailoverWatcherTest {

    private final AtomicReference<Optional<String>> reported = new AtomicReference<>(Optional.of("redis:6379"));
    private final AtomicInteger reconnects = new AtomicInteger();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SentinelFailoverWatcher watcher = new SentinelFailoverWatcher(
            reported::get, reconnects::incrementAndGet, new RateLimitMetrics(registry));

    @Test
    void firstAnswerIsRecordedWithoutReconnecting() {
        watcher.poll();

        assertThat(watcher.currentPrimary()).contains("redis:6379");
        assertThat(reconnects).hasValue(0);
    }

    @Test
    void sameAnswerDoesNotReconnect() {
        watcher.poll();
        watcher.poll();
        watcher.poll();

        assertThat(reconnects).hasValue(0);
    }

    @Test
    void newPrimaryRebuildsConnectionsOnce() {
        watcher.poll();
        reported.set(Optional.of("redis-replica:6379"));

        watcher.poll();
        watcher.poll();

        assertThat(reconnects).hasValue(1);
        assertThat(watcher.currentPrimary()).contains("redis-replica:6379");
        assertThat(registry.get("redis_failovers_observed_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void unreachableSentinelsKeepTheLastKnownPrimary() {
        watcher.poll();
        reported.set(Optional.empty());

        watcher.poll();
        reported.set(Optional.of("redis:6379"));
        watcher.poll();

        assertThat(reconnects).as("an outage of Sentinel is not a failover").hasValue(0);
        assertThat(watcher.currentPrimary()).contains("redis:6379");
    }

    @Test
    void failingBackReconnectsAgain() {
        watcher.poll();
        reported.set(Optional.of("redis-replica:6379"));
        watcher.poll();
        reported.set(Optional.of("redis:6379"));
        watcher.poll();

        assertThat(reconnects).hasValue(2);
    }
}
