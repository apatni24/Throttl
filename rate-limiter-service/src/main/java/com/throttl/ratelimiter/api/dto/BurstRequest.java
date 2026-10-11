package com.throttl.ratelimiter.api.dto;

public record BurstRequest(RateLimitCheckRequest context, int count) {
}
