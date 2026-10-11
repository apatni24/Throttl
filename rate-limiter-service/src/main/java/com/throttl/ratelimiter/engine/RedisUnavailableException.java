package com.throttl.ratelimiter.engine;

/** Raised without touching Redis while the circuit breaker is open. */
public class RedisUnavailableException extends RuntimeException {

    public RedisUnavailableException() {
        super("Redis circuit open", null, false, false);
    }
}
