package com.throttl.ratelimiter.domain;

public record TokenBucketConfiguration(long capacity, long refillRate, RefillPeriod refillPeriod)
        implements LimitConfiguration {

    public long refillPeriodMillis() {
        return refillPeriod.millis();
    }

    @Override
    public long burstLimit() {
        return capacity;
    }
}
