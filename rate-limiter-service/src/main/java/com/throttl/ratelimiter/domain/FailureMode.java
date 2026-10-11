package com.throttl.ratelimiter.domain;

/** What a policy decides when Redis cannot answer. */
public enum FailureMode {
    /** Deny. For limits that protect money or security (payments, login attempts). */
    FAIL_CLOSED,
    /** Allow. For limits where an outage of the limiter must not become an outage of the product. */
    FAIL_OPEN,
    /** Enforce the policy in process at {@code limit / instance count}: approximate, but still bounded. */
    LOCAL_FALLBACK
}
