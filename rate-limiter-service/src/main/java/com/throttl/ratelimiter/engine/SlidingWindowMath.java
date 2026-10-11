package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.domain.SlidingWindowConfiguration;

/**
 * Pure sliding-window-counter math matching {@code redis/rate_limit.lua}.
 * Windows are aligned to the epoch; the trailing-window count is estimated as
 * {@code previous * unexpiredFraction + current}.
 */
public final class SlidingWindowMath {

    private SlidingWindowMath() {
    }

    public record WindowState(long windowStart, long current, long previous) {
    }

    public record ConsumeResult(boolean allowed, long remaining, long retryAfterMs, WindowState state) {
    }

    public static ConsumeResult consume(WindowState state, SlidingWindowConfiguration configuration, long nowMs, long requested) {
        long limit = configuration.limit();
        long windowMs = configuration.windowMillis();
        long aligned = nowMs - Math.floorMod(nowMs, windowMs);

        long start;
        long current;
        long previous;
        if (state == null) {
            start = aligned;
            current = 0;
            previous = 0;
        } else if (aligned > state.windowStart()) {
            previous = aligned - state.windowStart() == windowMs ? state.current() : 0;
            current = 0;
            start = aligned;
        } else {
            start = state.windowStart();
            current = state.current();
            previous = state.previous();
        }

        long elapsed = Math.min(windowMs, Math.max(0, nowMs - start));
        double estimate = (double) (previous * (windowMs - elapsed)) / windowMs + current;
        long available = Math.max(0, (long) Math.floor(limit - estimate));

        if (available >= requested) {
            return new ConsumeResult(true, available - requested, 0, new WindowState(start, current + requested, previous));
        }
        return new ConsumeResult(false, available, retryAfter(limit, windowMs, elapsed, current, previous, requested),
                new WindowState(start, current, previous));
    }

    private static long retryAfter(long limit, long windowMs, long elapsed, long current, long previous, long requested) {
        long budget = limit - requested - current;
        double wait;
        if (budget >= 0 && previous > 0) {
            wait = windowMs - elapsed - (double) (budget * windowMs) / previous;
        } else if (current > 0) {
            wait = (windowMs - elapsed) + (double) (windowMs * (current - (limit - requested))) / current;
        } else {
            wait = windowMs - elapsed;
        }
        return Math.max(0, (long) Math.ceil(wait));
    }
}
