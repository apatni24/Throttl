package com.throttl.ratelimiter.domain;

/**
 * @param failureMode null inherits the service default ({@code throttl.fail-closed})
 */
public record PolicyMetadata(boolean enabled, int priority, FailureMode failureMode) {

    public PolicyMetadata(boolean enabled, int priority) {
        this(enabled, priority, null);
    }
}
