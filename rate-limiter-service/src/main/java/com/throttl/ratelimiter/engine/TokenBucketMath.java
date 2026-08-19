package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.domain.TokenBucketConfiguration;

/**
 * Pure token-bucket math matching the Redis Lua script. Used for unit tests
 * and as a reference for refill/consume/exhaustion behavior.
 */
public final class TokenBucketMath {

    private TokenBucketMath() {
    }

    public record BucketState(long tokens, long lastRefillTimestamp) {
    }

    public record ConsumeResult(boolean allowed, long remaining, long retryAfterMs, BucketState state) {
    }

    public static ConsumeResult consume(BucketState current, TokenBucketConfiguration configuration, long nowMs, long requested) {
        long capacity = configuration.capacity();
        long refillRate = configuration.refillRate();
        long refillPeriodMs = configuration.refillPeriodMillis();

        long tokens = current == null ? capacity : current.tokens();
        long last = current == null ? nowMs : current.lastRefillTimestamp();

        long elapsed = Math.max(0, nowMs - last);
        long periods = elapsed / refillPeriodMs;
        if (periods > 0) {
            tokens = Math.min(capacity, tokens + periods * refillRate);
            last = last + periods * refillPeriodMs;
        }

        if (tokens >= requested) {
            tokens -= requested;
            return new ConsumeResult(true, tokens, 0, new BucketState(tokens, last));
        }

        long deficit = requested - tokens;
        long periodsNeeded = (deficit + refillRate - 1) / refillRate;
        long retryAfter = Math.max(0, last + periodsNeeded * refillPeriodMs - nowMs);
        return new ConsumeResult(false, tokens, retryAfter, new BucketState(tokens, last));
    }
}
