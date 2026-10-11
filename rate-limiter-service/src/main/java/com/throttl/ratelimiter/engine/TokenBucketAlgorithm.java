package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.config.RateLimiterProperties;
import com.throttl.ratelimiter.domain.Policy;
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

@Component
public class TokenBucketAlgorithm implements RateLimitAlgorithm {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketAlgorithm.class);
    private static final String REQUESTED = "1";

    private final StringRedisTemplate redisTemplate;
    private final ReactiveStringRedisTemplate reactiveRedisTemplate;
    private final DefaultRedisScript<List> script;
    private final DefaultRedisScript<List> andScript;
    private final DefaultRedisScript<Long> refundScript;
    private final RateLimitMetrics metrics;
    private final RateLimiterProperties properties;

    public TokenBucketAlgorithm(
            StringRedisTemplate redisTemplate,
            ReactiveStringRedisTemplate reactiveRedisTemplate,
            RateLimitMetrics metrics,
            RateLimiterProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.reactiveRedisTemplate = reactiveRedisTemplate;
        this.metrics = metrics;
        this.properties = properties;
        this.script = new DefaultRedisScript<>();
        this.script.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/token_bucket.lua")));
        this.script.setResultType(List.class);
        this.andScript = new DefaultRedisScript<>();
        this.andScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/token_bucket_and.lua")));
        this.andScript.setResultType(List.class);
        this.refundScript = new DefaultRedisScript<>();
        this.refundScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/token_bucket_refund.lua")));
        this.refundScript.setResultType(Long.class);
    }

    @Override
    public RateLimitDecision evaluate(Policy policy, String bucketKey) {
        return evaluateAll(List.of(policy), List.of(bucketKey)).getFirst();
    }

    /**
     * Evaluates every matching bucket. Tokens are taken from all buckets only if
     * every bucket would allow; otherwise none stay taken.
     * <p>
     * Standalone Redis: one script over every key, fully atomic.
     * Redis Cluster: one atomic script per hash slot. If any slot group denies,
     * groups that already consumed are refunded. Between consume and refund a
     * concurrent check can be denied early; tokens are never overspent.
     */
    public List<RateLimitDecision> evaluateAll(List<Policy> policies, List<String> bucketKeys) {
        if (policies.size() != bucketKeys.size()) {
            throw new IllegalArgumentException("Policies and bucket keys must align");
        }
        if (policies.isEmpty()) {
            return List.of();
        }
        List<List<Integer>> groups = slotGroups(bucketKeys);
        Timer.Sample sample = Timer.start();
        try {
            return groups.size() == 1
                    ? evaluateGroup(policies, bucketKeys, groups.getFirst())
                    : evaluateAcrossSlots(policies, bucketKeys, groups);
        } catch (RuntimeException ex) {
            metrics.incrementRedisErrors();
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
        List<List<Integer>> groups = slotGroups(bucketKeys);
        Timer.Sample sample = Timer.start();
        Mono<List<RateLimitDecision>> result = groups.size() == 1
                ? evaluateGroupReactive(policies, bucketKeys, groups.getFirst())
                : evaluateAcrossSlotsReactive(policies, bucketKeys, groups);
        return result
                .doOnError(error -> metrics.incrementRedisErrors())
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

    private List<RateLimitDecision> evaluateGroup(List<Policy> policies, List<String> bucketKeys, List<Integer> group) {
        List<Policy> groupPolicies = pick(policies, group);
        List<String> groupKeys = pick(bucketKeys, group);
        if (groupPolicies.size() == 1) {
            return List.of(parseOne(groupPolicies.getFirst(), blockingOne(groupPolicies.getFirst(), groupKeys.getFirst())));
        }
        return parseAndResult(groupPolicies, blockingAnd(groupPolicies, groupKeys));
    }

    private Mono<List<RateLimitDecision>> evaluateGroupReactive(
            List<Policy> policies,
            List<String> bucketKeys,
            List<Integer> group
    ) {
        List<Policy> groupPolicies = pick(policies, group);
        List<String> groupKeys = pick(bucketKeys, group);
        if (groupPolicies.size() == 1) {
            Policy policy = groupPolicies.getFirst();
            return scriptResult(script, groupKeys, oneArgs(policy)).map(result -> List.of(parseOne(policy, result)));
        }
        return scriptResult(andScript, groupKeys, andArgs(groupPolicies))
                .map(result -> parseAndResult(groupPolicies, result));
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
                log.warn("Cross-slot refund failed for {}; tokens stay spent until refill", pick(bucketKeys, group), ex);
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
                long capacity = policies.get(index).strategy().configuration().capacity();
                adjusted[index] = RateLimitDecision.allow(Math.min(capacity, decisions[index].remaining() + 1));
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
    private List<Object> blockingAnd(List<Policy> policies, List<String> bucketKeys) {
        List<String> args = andArgs(policies);
        return (List<Object>) redisTemplate.execute(andScript, bucketKeys, (Object[]) args.toArray(new String[0]));
    }

    @SuppressWarnings("unchecked")
    private List<Object> blockingOne(Policy policy, String bucketKey) {
        List<String> args = oneArgs(policy);
        return (List<Object>) redisTemplate.execute(script, List.of(bucketKey), (Object[]) args.toArray(new String[0]));
    }

    private Mono<List<Object>> scriptResult(DefaultRedisScript<List> redisScript, List<String> keys, List<String> args) {
        return reactiveRedisTemplate.execute(redisScript, keys, args)
                .collectList()
                .map(TokenBucketAlgorithm::flattenScriptEmission);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> flattenScriptEmission(List<?> emitted) {
        if (emitted.size() == 1 && emitted.getFirst() instanceof List<?> inner) {
            return (List<Object>) inner;
        }
        return (List<Object>) emitted;
    }

    private static List<String> oneArgs(Policy policy) {
        TokenBucketConfiguration configuration = policy.strategy().configuration();
        return List.of(
                String.valueOf(configuration.capacity()),
                String.valueOf(configuration.refillRate()),
                String.valueOf(configuration.refillPeriodMillis()),
                REQUESTED
        );
    }

    private static List<String> andArgs(List<Policy> policies) {
        List<String> args = new ArrayList<>();
        args.add(REQUESTED);
        for (Policy policy : policies) {
            TokenBucketConfiguration configuration = policy.strategy().configuration();
            args.add(String.valueOf(configuration.capacity()));
            args.add(String.valueOf(configuration.refillRate()));
            args.add(String.valueOf(configuration.refillPeriodMillis()));
        }
        return args;
    }

    private static List<String> refundArgs(List<Policy> policies) {
        List<String> args = new ArrayList<>();
        args.add(REQUESTED);
        for (Policy policy : policies) {
            args.add(String.valueOf(policy.strategy().configuration().capacity()));
        }
        return args;
    }

    private List<RateLimitDecision> parseAndResult(List<Policy> policies, List<Object> result) {
        if (result == null || result.size() != 2 + policies.size() * 3) {
            throw new IllegalStateException("Unexpected Lua AND result");
        }
        List<RateLimitDecision> decisions = new ArrayList<>(policies.size());
        for (int i = 0; i < policies.size(); i++) {
            int offset = 2 + (i * 3);
            long remaining = asLong(result.get(offset));
            long retryAfterMs = asLong(result.get(offset + 1));
            boolean wouldAllow = asLong(result.get(offset + 2)) == 1L;
            Policy policy = policies.get(i);
            if (wouldAllow) {
                decisions.add(RateLimitDecision.allow(remaining));
            } else {
                decisions.add(RateLimitDecision.deny(remaining, retryAfterMs, policy.id()));
            }
        }
        return List.copyOf(decisions);
    }

    private static RateLimitDecision parseOne(Policy policy, List<Object> result) {
        if (result == null || result.size() < 3) {
            throw new IllegalStateException("Unexpected Lua result");
        }
        boolean allowed = asLong(result.get(0)) == 1L;
        long remaining = asLong(result.get(1));
        long retryAfterMs = asLong(result.get(2));
        if (allowed) {
            return RateLimitDecision.allow(remaining);
        }
        return RateLimitDecision.deny(remaining, retryAfterMs, policy.id());
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
