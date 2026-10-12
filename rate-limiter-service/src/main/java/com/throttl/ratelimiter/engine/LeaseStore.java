package com.throttl.ratelimiter.engine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import com.throttl.ratelimiter.metrics.RateLimitMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Units this instance already took from Redis for leased policies. Taking a
 * unit here is a local decrement; nothing is ever granted that Redis did not
 * count first. Units left when a lease expires are dropped, not returned:
 * that is the bounded under-admission leases trade for fewer round trips.
 */
@Component
public class LeaseStore {

    private final Ticker ticker;
    private final RateLimitMetrics metrics;
    private final Cache<String, Lease> leases;

    private static final class Lease {
        long remaining;
        long expiresAtNanos;

        Lease(long remaining, long expiresAtNanos) {
            this.remaining = remaining;
            this.expiresAtNanos = expiresAtNanos;
        }
    }

    @Autowired
    public LeaseStore(RateLimitMetrics metrics) {
        this(Ticker.systemTicker(), metrics);
    }

    LeaseStore(Ticker ticker, RateLimitMetrics metrics) {
        this.ticker = ticker;
        this.metrics = metrics;
        this.leases = Caffeine.newBuilder()
                .maximumSize(100_000)
                .ticker(ticker)
                .executor(Runnable::run)
                .expireAfter(new Expiry<String, Lease>() {
                    @Override
                    public long expireAfterCreate(String key, Lease lease, long currentTime) {
                        return Math.max(0, lease.expiresAtNanos - currentTime);
                    }

                    @Override
                    public long expireAfterUpdate(String key, Lease lease, long currentTime, long currentDuration) {
                        return Math.max(0, lease.expiresAtNanos - currentTime);
                    }

                    @Override
                    public long expireAfterRead(String key, Lease lease, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .removalListener((String key, Lease lease, RemovalCause cause) -> {
                    if (lease != null && cause != RemovalCause.REPLACED && cause != RemovalCause.EXPLICIT) {
                        long unused;
                        synchronized (lease) {
                            unused = lease.remaining;
                            lease.remaining = 0;
                        }
                        if (unused > 0) {
                            metrics.incrementLeaseUnitsExpired(unused);
                        }
                    }
                })
                .build();
    }

    /** Takes one leased unit if this instance holds a live lease with units left. */
    public boolean tryTake(String bucketKey) {
        Lease lease = leases.getIfPresent(bucketKey);
        if (lease == null) {
            return false;
        }
        synchronized (lease) {
            if (lease.remaining <= 0 || ticker.read() >= lease.expiresAtNanos) {
                return false;
            }
            lease.remaining--;
            return true;
        }
    }

    /** Returns a unit taken by {@link #tryTake} for a request another policy denied. */
    public void giveBack(String bucketKey) {
        Lease lease = leases.getIfPresent(bucketKey);
        if (lease == null) {
            return;
        }
        synchronized (lease) {
            if (ticker.read() < lease.expiresAtNanos) {
                lease.remaining++;
            }
        }
    }

    /** Adds units Redis just granted to a refill; the lease lives {@code ttlMs} from now. */
    public void add(String bucketKey, long units, long ttlMs) {
        if (units <= 0) {
            return;
        }
        long expiresAt = ticker.read() + TimeUnit.MILLISECONDS.toNanos(ttlMs);
        leases.asMap().compute(bucketKey, (key, existing) -> {
            if (existing == null) {
                return new Lease(units, expiresAt);
            }
            // Mutate in place: a concurrent tryTake may hold this instance, and
            // a replacement object would let it spend a unit twice.
            synchronized (existing) {
                if (ticker.read() >= existing.expiresAtNanos) {
                    if (existing.remaining > 0) {
                        metrics.incrementLeaseUnitsExpired(existing.remaining);
                    }
                    existing.remaining = 0;
                }
                existing.remaining += units;
                existing.expiresAtNanos = expiresAt;
                return existing;
            }
        });
    }

    public long remaining(String bucketKey) {
        Lease lease = leases.getIfPresent(bucketKey);
        if (lease == null) {
            return 0;
        }
        synchronized (lease) {
            return lease.remaining;
        }
    }

    public void clear() {
        leases.invalidateAll();
    }
}
