package com.throttl.ratelimiter.engine;

/**
 * One bucket's answer from {@code rate_limit.lua}.
 *
 * @param granted units actually taken: 0 unless every bucket in the request
 *                allowed; more than 1 only for a lease refill
 */
public record BucketOutcome(boolean allowed, long remaining, long retryAfterMs, long granted) {

    RateLimitDecision toDecision(String policyId) {
        return allowed
                ? RateLimitDecision.allow(remaining)
                : RateLimitDecision.deny(remaining, retryAfterMs, policyId);
    }
}
