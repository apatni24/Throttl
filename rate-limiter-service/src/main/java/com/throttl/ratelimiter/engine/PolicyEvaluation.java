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
    /** Redis unavailable; the policy's failure mode allowed the request. */
    public static final String FAILED_OPEN = "FAILED_OPEN";
    /** Redis unavailable; the policy's failure mode denied the request. */
    public static final String FAILED_CLOSED = "FAILED_CLOSED";
    /** Redis unavailable; decided by the in-process fallback limiter. */
    public static final String LOCAL_ALLOWED = "LOCAL_ALLOWED";
    public static final String LOCAL_REJECTED = "LOCAL_REJECTED";
}
