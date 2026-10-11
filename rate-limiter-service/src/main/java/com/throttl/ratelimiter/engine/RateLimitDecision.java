package com.throttl.ratelimiter.engine;

import java.util.List;

/**
 * @param degraded true when Redis could not be consulted and the decision came
 *                 from the matching policies' failure modes
 */
public record RateLimitDecision(
        boolean allowed,
        long remaining,
        long retryAfterMs,
        String rejectedBy,
        List<PolicyEvaluation> evaluations,
        boolean degraded
) {

    public RateLimitDecision {
        evaluations = evaluations == null ? List.of() : List.copyOf(evaluations);
    }

    public static RateLimitDecision allow(long remaining) {
        return new RateLimitDecision(true, remaining, 0, null, List.of(), false);
    }

    public static RateLimitDecision deny(long remaining, long retryAfterMs, String rejectedBy) {
        return new RateLimitDecision(false, remaining, retryAfterMs, rejectedBy, List.of(), false);
    }

    public RateLimitDecision withEvaluations(List<PolicyEvaluation> next) {
        return new RateLimitDecision(allowed, remaining, retryAfterMs, rejectedBy, next, degraded);
    }

    public RateLimitDecision asDegraded() {
        return new RateLimitDecision(allowed, remaining, retryAfterMs, rejectedBy, evaluations, true);
    }
}
