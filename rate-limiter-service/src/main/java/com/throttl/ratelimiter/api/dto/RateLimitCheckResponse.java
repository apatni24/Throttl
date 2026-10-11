package com.throttl.ratelimiter.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.throttl.ratelimiter.engine.PolicyEvaluation;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RateLimitCheckResponse(
        boolean allowed,
        long remaining,
        long retryAfterMs,
        String rejectedBy,
        List<PolicyEvaluation> evaluations
) {
}
