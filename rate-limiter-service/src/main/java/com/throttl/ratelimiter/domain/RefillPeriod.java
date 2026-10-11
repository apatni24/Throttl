package com.throttl.ratelimiter.domain;

public enum RefillPeriod {
    SECOND(1_000L),
    MINUTE(60_000L),
    HOUR(3_600_000L);

    private final long millis;

    RefillPeriod(long millis) {
        this.millis = millis;
    }

    public long millis() {
        return millis;
    }
}
