package com.throttl.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "throttl")
public record RateLimiterProperties(
        long cacheTtlSeconds,
        boolean failClosed
) {
}
