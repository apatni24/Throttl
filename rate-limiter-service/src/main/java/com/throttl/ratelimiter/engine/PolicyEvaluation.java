package com.throttl.ratelimiter.engine;

public record PolicyEvaluation(
        String policyId,
        String status,
        long remaining,
        long retryAfterMs
) {
    public static final String ALLOWED = "ALLOWED";
    public static final String REJECTED = "REJECTED";
    public static final String SKIPPED = "SKIPPED";
}
