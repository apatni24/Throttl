package com.throttl.ratelimiter.engine;

import java.util.List;

public record RateLimitDecision(
        boolean allowed,
        long remaining,
        long retryAfterMs,
        String rejectedBy,
        List<PolicyEvaluation> evaluations
) {

    public RateLimitDecision {
        evaluations = evaluations == null ? List.of() : List.copyOf(evaluations);
    }

    public static RateLimitDecision allow(long remaining) {
        return new RateLimitDecision(true, remaining, 0, null, List.of());
    }

    public static RateLimitDecision deny(long remaining, long retryAfterMs, String rejectedBy) {
        return new RateLimitDecision(false, remaining, retryAfterMs, rejectedBy, List.of());
    }

    public RateLimitDecision withEvaluations(List<PolicyEvaluation> next) {
        return new RateLimitDecision(allowed, remaining, retryAfterMs, rejectedBy, next);
    }
}
