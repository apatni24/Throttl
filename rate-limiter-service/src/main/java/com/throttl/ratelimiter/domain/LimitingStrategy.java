package com.throttl.ratelimiter.domain;

/**
 * @param lease null = every check goes to Redis (exact)
 */
public record LimitingStrategy(Algorithm algorithm, LimitConfiguration configuration, LeaseConfiguration lease) {

    public LimitingStrategy(Algorithm algorithm, LimitConfiguration configuration) {
        this(algorithm, configuration, null);
    }
}
