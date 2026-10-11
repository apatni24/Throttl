package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.config.RateLimiterProperties;
import com.throttl.ratelimiter.domain.LimitConfiguration;
import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.domain.SlidingWindowConfiguration;
import com.throttl.ratelimiter.domain.TokenBucketConfiguration;
import com.throttl.ratelimiter.metrics.RateLimitMetrics;
import io.lettuce.core.cluster.SlotHash;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Runs bucket checks in Redis. Every algorithm is evaluated by the same Lua
 * script, so one request that matches a token-bucket policy and a
 * sliding-window policy is still all-or-nothing.
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final long REQUESTED = 1L;
    private static final String TOKEN_BUCKET = "1";
    private static final String SLIDING_WINDOW = "2";

    private final StringRedisTemplate redisTemplate;
    private final ReactiveStringRedisTemplate reactiveRedisTemplate;
    private final DefaultRedisScript<List> script;
    private final DefaultRedisScript<Long> refundScript;
    private final RateLimitMetrics metrics;
    private final RateLimiterProperties properties;
    private final RedisCircuitBreaker breaker;

    public RedisRateLimiter(
            StringRedisTemplate redisTemplate,
            ReactiveStringRedisTemplate reactiveRedisTemplate,
            RateLimitMetrics metrics,
            RateLimiterProperties properties,
            RedisCircuitBreaker breaker
    ) {
        this.redisTemplate = redisTemplate;
        this.reactiveRedisTemplate = reactiveRedisTemplate;
        this.metrics = metrics;
        this.properties = properties;
        this.breaker = breaker;
        this.script = new DefaultRedisScript<>();
        this.script.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/rate_limit.lua")));
        this.script.setResultType(List.class);
        this.refundScript = new DefaultRedisScript<>();
        this.refundScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/rate_limit_refund.lua")));
        this.refundScript.setResultType(Long.class);
    }

    public RateLimitDecision evaluate(Policy policy, String bucketKey) {
        return evaluateAll(List.of(policy), List.of(bucketKey)).getFirst();
    }

    /**
     * Evaluates every matching bucket. Units are taken from all buckets only if
     * every bucket would allow; otherwise none stay taken.
     * <p>
     * Standalone Redis: one script over every key, fully atomic.
     * Redis Cluster: one atomic script per hash slot. If any slot group denies,
     * groups that already consumed are refunded. Between consume and refund a
     * concurrent check can be denied early; capacity is never overspent.
     */
    public List<RateLimitDecision> evaluateAll(List<Policy> policies, List<String> bucketKeys) {
        if (policies.size() != bucketKeys.size()) {
            throw new IllegalArgumentException("Policies and bucket keys must align");
        }
        if (policies.isEmpty()) {
            return List.of();
        }
        if (!breaker.allowRequest()) {
            metrics.incrementRedisShortCircuited();
            throw new RedisUnavailableException();
        }
        List<List<Integer>> groups = slotGroups(bucketKeys);
        Timer.Sample sample = Timer.start();
        try {
            List<RateLimitDecision> decisions = groups.size() == 1
                    ? evaluateGroup(policies, bucketKeys, groups.getFirst())
                    : evaluateAcrossSlots(policies, bucketKeys, groups);
            breaker.onSuccess();
            return decisions;
        } catch (RuntimeException ex) {
            metrics.incrementRedisErrors();
            breaker.onFailure(ex);
            throw ex;
        } finally {
            sample.stop(metrics.redisOperationTimer());
        }
    }

    public Mono<List<RateLimitDecision>> evaluateAllReactive(List<Policy> policies, List<String> bucketKeys) {
        if (policies.size() != bucketKeys.size()) {
            return Mono.error(new IllegalArgumentException("Policies and bucket keys must align"));
        }
        if (policies.isEmpty()) {
            return Mono.just(List.of());
        }
        if (!breaker.allowRequest()) {
            metrics.incrementRedisShortCircuited();
            return Mono.error(new RedisUnavailableException());
        }
        List<List<Integer>> groups = slotGroups(bucketKeys);
        Timer.Sample sample = Timer.start();
        Mono<List<RateLimitDecision>> result = groups.size() == 1
                ? evaluateGroupReactive(policies, bucketKeys, groups.getFirst())
                : evaluateAcrossSlotsReactive(policies, bucketKeys, groups);
        return result
                .doOnSuccess(decisions -> breaker.onSuccess())
                .doOnError(error -> {
                    metrics.incrementRedisErrors();
                    breaker.onFailure(error);
                })
                .doFinally(signal -> sample.stop(metrics.redisOperationTimer()));
    }

    private List<List<Integer>> slotGroups(List<String> bucketKeys) {
        if (!properties.redisCluster()) {
            return List.of(IntStream.range(0, bucketKeys.size()).boxed().toList());
        }
        Map<Integer, List<Integer>> bySlot = new LinkedHashMap<>();
        for (int i = 0; i < bucketKeys.size(); i++) {
            bySlot.computeIfAbsent(SlotHash.getSlot(bucketKeys.get(i)), slot -> new ArrayList<>()).add(i);
        }
        return List.copyOf(bySlot.values());
    }

    @SuppressWarnings("unchecked")
    private List<RateLimitDecision> evaluateGroup(List<Policy> policies, List<String> bucketKeys, List<Integer> group) {
        List<Policy> groupPolicies = pick(policies, group);
        List<String> args = scriptArgs(groupPolicies);
        List<Object> result = (List<Object>) redisTemplate.execute(
                script, pick(bucketKeys, group), (Object[]) args.toArray(new String[0]));
        return parse(groupPolicies, result);
    }

    private Mono<List<RateLimitDecision>> evaluateGroupReactive(
            List<Policy> policies,
            List<String> bucketKeys,
            List<Integer> group
    ) {
        List<Policy> groupPolicies = pick(policies, group);
        return reactiveRedisTemplate.execute(script, pick(bucketKeys, group), scriptArgs(groupPolicies))
                .collectList()
                .map(RedisRateLimiter::flattenScriptEmission)
                .map(result -> parse(groupPolicies, result));
    }

    /**
     * Runs on one bound connection so the refund never has to borrow from a
     * pool that concurrent checks have exhausted.
     */
    private List<RateLimitDecision> evaluateAcrossSlots(
            List<Policy> policies,
            List<String> bucketKeys,
            List<List<Integer>> groups
    ) {
        return redisTemplate.execute(new SessionCallback<>() {
            @Override
            public <K, V> List<RateLimitDecision> execute(RedisOperations<K, V> operations) {
                return evaluateAcrossSlotsOnBoundConnection(policies, bucketKeys, groups);
            }
        });
    }

    private List<RateLimitDecision> evaluateAcrossSlotsOnBoundConnection(
            List<Policy> policies,
            List<String> bucketKeys,
            List<List<Integer>> groups
    ) {
        RateLimitDecision[] decisions = new RateLimitDecision[policies.size()];
        List<List<Integer>> consumed = new ArrayList<>();
        try {
            for (List<Integer> group : groups) {
                List<RateLimitDecision> groupDecisions = evaluateGroup(policies, bucketKeys, group);
                place(decisions, group, groupDecisions);
                if (allAllowed(groupDecisions)) {
                    consumed.add(group);
                }
            }
        } catch (RuntimeException ex) {
            refund(policies, bucketKeys, consumed);
            throw ex;
        }
        if (consumed.size() == groups.size()) {
            return List.of(decisions);
        }
        refund(policies, bucketKeys, consumed);
        return afterRefund(policies, decisions, consumed);
    }

    /**
     * Reactive connections cannot be pinned across commands, so the cross-slot
     * case reuses the bound-connection path on boundedElastic.
     */
    private Mono<List<RateLimitDecision>> evaluateAcrossSlotsReactive(
            List<Policy> policies,
            List<String> bucketKeys,
            List<List<Integer>> groups
    ) {
        return Mono.fromCallable(() -> evaluateAcrossSlots(policies, bucketKeys, groups))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void refund(List<Policy> policies, List<String> bucketKeys, List<List<Integer>> groups) {
        for (List<Integer> group : groups) {
            try {
                redisTemplate.execute(
                        refundScript,
                        pick(bucketKeys, group),
                        (Object[]) refundArgs(pick(policies, group)).toArray(new String[0])
                );
                metrics.incrementCrossSlotRefunds();
            } catch (RuntimeException ex) {
                metrics.incrementRedisErrors();
                log.warn("Cross-slot refund failed for {}; capacity stays spent until refill", pick(bucketKeys, group), ex);
            }
        }
    }

    private static List<RateLimitDecision> afterRefund(
            List<Policy> policies,
            RateLimitDecision[] decisions,
            List<List<Integer>> refunded
    ) {
        RateLimitDecision[] adjusted = decisions.clone();
        for (List<Integer> group : refunded) {
            for (int index : group) {
                long limit = policies.get(index).strategy().configuration().burstLimit();
                adjusted[index] = RateLimitDecision.allow(Math.min(limit, decisions[index].remaining() + REQUESTED));
            }
        }
        return List.of(adjusted);
    }

    private static void place(RateLimitDecision[] decisions, List<Integer> group, List<RateLimitDecision> groupDecisions) {
        for (int i = 0; i < group.size(); i++) {
            decisions[group.get(i)] = groupDecisions.get(i);
        }
    }

    private static boolean allAllowed(List<RateLimitDecision> decisions) {
        return decisions.stream().allMatch(RateLimitDecision::allowed);
    }

    private static <T> List<T> pick(List<T> items, List<Integer> indices) {
        if (indices.size() == items.size()) {
            return items;
        }
        return indices.stream().map(items::get).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> flattenScriptEmission(List<?> emitted) {
        if (emitted.size() == 1 && emitted.getFirst() instanceof List<?> inner) {
            return (List<Object>) inner;
        }
        return (List<Object>) emitted;
    }

    private static List<String> scriptArgs(List<Policy> policies) {
        List<String> args = new ArrayList<>(1 + policies.size() * 4);
        args.add(String.valueOf(REQUESTED));
        for (Policy policy : policies) {
            switch (policy.strategy().configuration()) {
                case TokenBucketConfiguration tokenBucket -> {
                    args.add(TOKEN_BUCKET);
                    args.add(String.valueOf(tokenBucket.capacity()));
                    args.add(String.valueOf(tokenBucket.refillRate()));
                    args.add(String.valueOf(tokenBucket.refillPeriodMillis()));
                }
                case SlidingWindowConfiguration slidingWindow -> {
                    args.add(SLIDING_WINDOW);
                    args.add(String.valueOf(slidingWindow.limit()));
                    args.add(String.valueOf(slidingWindow.windowMillis()));
                    args.add("0");
                }
            }
        }
        return args;
    }

    private static List<String> refundArgs(List<Policy> policies) {
        List<String> args = new ArrayList<>(policies.size() * 3);
        for (Policy policy : policies) {
            LimitConfiguration configuration = policy.strategy().configuration();
            args.add(configuration instanceof SlidingWindowConfiguration ? SLIDING_WINDOW : TOKEN_BUCKET);
            args.add(String.valueOf(REQUESTED));
            args.add(String.valueOf(configuration.burstLimit()));
        }
        return args;
    }

    private static List<RateLimitDecision> parse(List<Policy> policies, List<Object> result) {
        if (result == null || result.size() != 2 + policies.size() * 3) {
            throw new IllegalStateException("Unexpected rate_limit.lua result");
        }
        List<RateLimitDecision> decisions = new ArrayList<>(policies.size());
        for (int i = 0; i < policies.size(); i++) {
            int offset = 2 + (i * 3);
            long remaining = asLong(result.get(offset));
            long retryAfterMs = asLong(result.get(offset + 1));
            boolean wouldAllow = asLong(result.get(offset + 2)) == 1L;
            decisions.add(wouldAllow
                    ? RateLimitDecision.allow(remaining)
                    : RateLimitDecision.deny(remaining, retryAfterMs, policies.get(i).id()));
        }
        return List.copyOf(decisions);
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            return Long.parseLong(text);
        }
        throw new IllegalStateException("Unexpected Lua value: " + value);
    }
}
