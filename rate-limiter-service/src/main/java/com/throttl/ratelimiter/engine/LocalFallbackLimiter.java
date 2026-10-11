package com.throttl.ratelimiter.engine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.throttl.ratelimiter.config.RateLimiterProperties;
import com.throttl.ratelimiter.domain.LimitConfiguration;
import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.domain.SlidingWindowConfiguration;
import com.throttl.ratelimiter.domain.TokenBucketConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * In-process enforcement for {@code LOCAL_FALLBACK} policies while Redis is
 * unreachable. Each instance enforces its share of the limit,
 * {@code max(1, limit / instances)}, so the fleet stays near the global limit
 * without coordinating. Rounding down errs toward denial; the floor of 1 means
 * tiny limits can be exceeded by up to {@code instances - 1}.
 * <p>
 * State is per instance and never written back to Redis, so units spent here
 * are not visible to Redis once it recovers.
 */
@Component
public class LocalFallbackLimiter {

    private final int instances;
    private final LongSupplier clock;
    private final Cache<String, Object> states = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    @Autowired
    public LocalFallbackLimiter(RateLimiterProperties properties) {
        this(Math.max(1, properties.getInstances().size()), System::currentTimeMillis);
    }

    LocalFallbackLimiter(int instances, LongSupplier clock) {
        this.instances = Math.max(1, instances);
        this.clock = clock;
    }

    public int instances() {
        return instances;
    }

    /** All-or-nothing across the given buckets, mirroring the Redis script. */
    public synchronized List<RateLimitDecision> tryConsumeAll(List<Policy> policies, List<String> bucketKeys) {
        long now = clock.getAsLong();
        List<Object> nextStates = new ArrayList<>(policies.size());
        List<RateLimitDecision> decisions = new ArrayList<>(policies.size());
        boolean allAllowed = true;
        for (int i = 0; i < policies.size(); i++) {
            Policy policy = policies.get(i);
            Object state = states.getIfPresent(stateKey(policy, bucketKeys.get(i)));
            switch (share(policy.strategy().configuration())) {
                case TokenBucketConfiguration config -> {
                    TokenBucketMath.ConsumeResult result = TokenBucketMath.consume(
                            state instanceof TokenBucketMath.BucketState s ? s : null, config, now, 1);
                    nextStates.add(result.state());
                    decisions.add(decision(result.allowed(), result.remaining(), result.retryAfterMs(), policy));
                }
                case SlidingWindowConfiguration config -> {
                    SlidingWindowMath.ConsumeResult result = SlidingWindowMath.consume(
                            state instanceof SlidingWindowMath.WindowState s ? s : null, config, now, 1);
                    nextStates.add(result.state());
                    decisions.add(decision(result.allowed(), result.remaining(), result.retryAfterMs(), policy));
                }
            }
            allAllowed &= decisions.getLast().allowed();
        }
        if (allAllowed) {
            for (int i = 0; i < policies.size(); i++) {
                states.put(stateKey(policies.get(i), bucketKeys.get(i)), nextStates.get(i));
            }
        }
        return List.copyOf(decisions);
    }

    public void clear() {
        states.invalidateAll();
    }

    LimitConfiguration share(LimitConfiguration configuration) {
        return switch (configuration) {
            case TokenBucketConfiguration tb -> new TokenBucketConfiguration(
                    perInstance(tb.capacity()), perInstance(tb.refillRate()), tb.refillPeriod());
            case SlidingWindowConfiguration sw -> new SlidingWindowConfiguration(
                    perInstance(sw.limit()), sw.window());
        };
    }

    private long perInstance(long value) {
        return Math.max(1, value / instances);
    }

    private static String stateKey(Policy policy, String bucketKey) {
        return policy.strategy().algorithm() + "|" + bucketKey;
    }

    private static RateLimitDecision decision(boolean allowed, long remaining, long retryAfterMs, Policy policy) {
        return allowed
                ? RateLimitDecision.allow(remaining)
                : RateLimitDecision.deny(remaining, retryAfterMs, policy.id());
    }
}
