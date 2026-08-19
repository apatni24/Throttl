package com.throttl.ratelimiter.domain;

public class PolicyValidationException extends RuntimeException {

    public PolicyValidationException(String message) {
        super(message);
    }
}
