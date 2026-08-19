package com.throttl.ratelimiter.store;

public class PolicyNotFoundException extends RuntimeException {

    public PolicyNotFoundException(String id) {
        super("Policy not found: " + id);
    }
}
