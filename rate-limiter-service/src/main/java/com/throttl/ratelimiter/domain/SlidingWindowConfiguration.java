package com.throttl.ratelimiter.domain;

/**
 * Sliding window counter: at most {@code limit} requests in any trailing
 * {@code window}, estimated from the current and previous fixed windows.
 */
public record SlidingWindowConfiguration(long limit, RefillPeriod window) implements LimitConfiguration {

    public long windowMillis() {
        return window.millis();
    }

    @Override
    public long burstLimit() {
        return limit;
    }
}
