package com.throttl.ratelimiter.engine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.throttl.ratelimiter.config.RateLimiterProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Remembers buckets Redis just denied, until the {@code retryAfterMs} Redis
 * computed (capped). A denied key cannot allow before then (other instances
 * only consume), so answering locally never over-admits; a request over its
 * limit stops costing a Redis round trip. The cap bounds the one case where
 * capacity returns early: a cross-slot refund.
 */
@Component
public class LocalDenyCache {

    private final boolean enabled;
    private final long maxMillis;
    private final Ticker ticker;
    private final Cache<String, Long> deniedUntilNanos;

    @Autowired
    public LocalDenyCache(RateLimiterProperties properties) {
        this(properties.denyCacheEnabled(), properties.denyCacheMaxMs(), Ticker.systemTicker());
    }

    LocalDenyCache(boolean enabled, long maxMillis, Ticker ticker) {
        this.enabled = enabled;
        this.maxMillis = maxMillis;
        this.ticker = ticker;
        this.deniedUntilNanos = Caffeine.newBuilder()
                .maximumSize(100_000)
                .ticker(ticker)
                .expireAfter(new Expiry<String, Long>() {
                    @Override
                    public long expireAfterCreate(String key, Long until, long currentTime) {
                        return Math.max(0, until - currentTime);
                    }

                    @Override
                    public long expireAfterUpdate(String key, Long until, long currentTime, long currentDuration) {
                        return Math.max(0, until - currentTime);
                    }

                    @Override
                    public long expireAfterRead(String key, Long until, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    public void deny(String bucketKey, long retryAfterMs) {
        if (!enabled || retryAfterMs <= 0) {
            return;
        }
        long millis = Math.min(retryAfterMs, maxMillis);
        deniedUntilNanos.put(bucketKey, ticker.read() + TimeUnit.MILLISECONDS.toNanos(millis));
    }

    /** Milliseconds this bucket is still known to deny, or 0. */
    public long retryAfterMs(String bucketKey) {
        if (!enabled) {
            return 0;
        }
        Long until = deniedUntilNanos.getIfPresent(bucketKey);
        if (until == null) {
            return 0;
        }
        long remainingNanos = until - ticker.read();
        return remainingNanos <= 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    public void clear() {
        deniedUntilNanos.invalidateAll();
    }
}
