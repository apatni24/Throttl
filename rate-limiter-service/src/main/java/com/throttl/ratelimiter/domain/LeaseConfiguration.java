package com.throttl.ratelimiter.domain;

/**
 * Opt-in token batching: an instance takes up to {@code tokens} units from
 * Redis in one call and spends them locally for at most {@code ttlMs}.
 * Redis never counts more than the limit, but admissions can happen up to
 * {@code ttlMs} after Redis counted them, and unused units expire unspent.
 */
public record LeaseConfiguration(int tokens, long ttlMs) {
}
