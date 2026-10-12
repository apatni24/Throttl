package com.throttl.ratelimiter.config;

import com.throttl.ratelimiter.metrics.RateLimitMetrics;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.sentinel.api.StatefulRedisSentinelConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Moves Redis connections to the new primary as soon as Sentinel promotes it.
 * <p>
 * Lettuce asks Sentinel for the primary only when it opens a connection. If the
 * old primary hangs instead of dying (frozen process, one-way partition) its TCP
 * connections stay open, every command times out, and nothing reconnects until
 * the old node comes back and Sentinel kills its clients. So each instance asks
 * Sentinel for the primary every {@code throttl.sentinel-poll-ms} and rebuilds
 * the connection factory when the answer changes.
 */
@Component
@ConditionalOnProperty(prefix = "spring.data.redis.sentinel", name = "master")
public class SentinelFailoverWatcher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SentinelFailoverWatcher.class);
    private static final Duration SENTINEL_TIMEOUT = Duration.ofMillis(500);

    private final Supplier<Optional<String>> primaryLookup;
    private final Runnable reconnect;
    private final RateLimitMetrics metrics;
    private final AtomicReference<String> primary = new AtomicReference<>();
    private final List<Sentinel> sentinels = new ArrayList<>();
    private RedisClient client;
    private ScheduledExecutorService scheduler;

    @Autowired
    public SentinelFailoverWatcher(
            RedisProperties redis,
            LettuceConnectionFactory connectionFactory,
            RateLimiterProperties properties,
            RateLimitMetrics metrics
    ) {
        this.metrics = metrics;
        this.reconnect = () -> {
            connectionFactory.stop();
            connectionFactory.start();
        };
        RedisProperties.Sentinel config = redis.getSentinel();
        this.client = RedisClient.create();
        this.client.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(SENTINEL_TIMEOUT).build())
                .build());
        for (String node : config.getNodes()) {
            int colon = node.lastIndexOf(':');
            RedisURI.Builder uri = RedisURI.builder()
                    .withHost(node.substring(0, colon))
                    .withPort(Integer.parseInt(node.substring(colon + 1)))
                    .withTimeout(SENTINEL_TIMEOUT);
            if (config.getPassword() != null) {
                uri.withPassword(config.getPassword().toCharArray());
            }
            sentinels.add(new Sentinel(uri.build()));
        }
        String master = config.getMaster();
        this.primaryLookup = () -> askSentinels(master);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sentinel-watcher");
            thread.setDaemon(true);
            return thread;
        });
        long pollMs = properties.sentinelPollMs();
        scheduler.scheduleWithFixedDelay(this::pollSafely, 0, pollMs, TimeUnit.MILLISECONDS);
        log.info("Watching Sentinel master '{}' via {} every {} ms", master, config.getNodes(), pollMs);
    }

    SentinelFailoverWatcher(Supplier<Optional<String>> primaryLookup, Runnable reconnect, RateLimitMetrics metrics) {
        this.primaryLookup = primaryLookup;
        this.reconnect = reconnect;
        this.metrics = metrics;
    }

    /** The primary Sentinel last reported, as host:port. */
    public Optional<String> currentPrimary() {
        return Optional.ofNullable(primary.get());
    }

    void poll() {
        Optional<String> reported = primaryLookup.get();
        if (reported.isEmpty()) {
            return;
        }
        String now = reported.get();
        String before = primary.getAndSet(now);
        if (before != null && !before.equals(now)) {
            log.warn("Sentinel reports primary {} (was {}); rebuilding Redis connections", now, before);
            metrics.incrementRedisFailovers();
            reconnect.run();
        }
    }

    private void pollSafely() {
        try {
            poll();
        } catch (RuntimeException ex) {
            log.warn("Sentinel poll failed", ex);
        }
    }

    private Optional<String> askSentinels(String master) {
        for (Sentinel sentinel : sentinels) {
            Optional<String> answer = sentinel.primary(master);
            if (answer.isPresent()) {
                return answer;
            }
        }
        return Optional.empty();
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        sentinels.forEach(Sentinel::close);
        if (client != null) {
            client.shutdown();
        }
    }

    private final class Sentinel {
        private final RedisURI uri;
        private StatefulRedisSentinelConnection<String, String> connection;

        Sentinel(RedisURI uri) {
            this.uri = uri;
        }

        Optional<String> primary(String master) {
            try {
                if (connection == null) {
                    connection = client.connectSentinel(uri);
                }
                SocketAddress address = connection.sync().getMasterAddrByName(master);
                if (address instanceof InetSocketAddress inet) {
                    return Optional.of(inet.getHostString() + ":" + inet.getPort());
                }
                return Optional.empty();
            } catch (RuntimeException ex) {
                log.debug("Sentinel {} did not answer", uri, ex);
                close();
                return Optional.empty();
            }
        }

        void close() {
            if (connection != null) {
                connection.closeAsync();
                connection = null;
            }
        }
    }
}
