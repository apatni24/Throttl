package com.throttl.ratelimiter.engine;

public record RateLimitDecision(boolean allowed, long remaining, long retryAfterMs, String rejectedBy) {

    public static RateLimitDecision allow(long remaining) {
        return new RateLimitDecision(true, remaining, 0, null);
    }

    public static RateLimitDecision deny(long remaining, long retryAfterMs, String rejectedBy) {
        return new RateLimitDecision(false, remaining, retryAfterMs, rejectedBy);
    }
}
