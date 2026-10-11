package com.throttl.ratelimiter.domain;

public sealed interface LimitConfiguration permits TokenBucketConfiguration, SlidingWindowConfiguration {

    /** Most requests the policy can admit back to back from a fresh state. */
    long burstLimit();
}
