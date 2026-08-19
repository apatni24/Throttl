package com.throttl.ratelimiter.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RateLimitCheckResponse(
        boolean allowed,
        long remaining,
        long retryAfterMs,
        String rejectedBy
) {
}
