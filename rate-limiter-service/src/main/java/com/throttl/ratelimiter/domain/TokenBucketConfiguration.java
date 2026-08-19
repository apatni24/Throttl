package com.throttl.ratelimiter.domain;

public record TokenBucketConfiguration(long capacity, long refillRate, RefillPeriod refillPeriod) {

    public long refillPeriodMillis() {
        return switch (refillPeriod) {
            case SECOND -> 1_000L;
            case MINUTE -> 60_000L;
            case HOUR -> 3_600_000L;
        };
    }
}
