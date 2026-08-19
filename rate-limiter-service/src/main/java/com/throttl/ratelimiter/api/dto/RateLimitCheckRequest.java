package com.throttl.ratelimiter.api.dto;

public record RateLimitCheckRequest(String userId, String tenantId, String api, String ip) {
}
