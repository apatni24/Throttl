package com.throttl.ratelimiter.domain;

public record LimitingStrategy(Algorithm algorithm, LimitConfiguration configuration) {
}
