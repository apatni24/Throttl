package com.throttl.demo.client;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RateLimitDecision(boolean allowed, long remaining, long retryAfterMs, String rejectedBy) {
}
