package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.domain.TokenBucketConfiguration;
import com.throttl.ratelimiter.metrics.RateLimitMetrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

@Component
public class TokenBucketAlgorithm implements RateLimitAlgorithm {

    private final StringRedisTemplate redisTemplate;
    private final ReactiveStringRedisTemplate reactiveRedisTemplate;
    private final DefaultRedisScript<List> script;
    private final DefaultRedisScript<List> andScript;
    private final RateLimitMetrics metrics;

    public TokenBucketAlgorithm(
            StringRedisTemplate redisTemplate,
            ReactiveStringRedisTemplate reactiveRedisTemplate,
            RateLimitMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.reactiveRedisTemplate = reactiveRedisTemplate;
        this.metrics = metrics;
        this.script = new DefaultRedisScript<>();
        this.script.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/token_bucket.lua")));
        this.script.setResultType(List.class);
        this.andScript = new DefaultRedisScript<>();
        this.andScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/token_bucket_and.lua")));
        this.andScript.setResultType(List.class);
    }

    @Override
    public RateLimitDecision evaluate(Policy policy, String bucketKey) {
        return evaluateAll(List.of(policy), List.of(bucketKey)).getFirst();
    }

    /**
     * Evaluates every matching bucket in one Redis script. Tokens are taken from
     * all buckets only if every bucket would allow; otherwise none are taken.
     */
    public List<RateLimitDecision> evaluateAll(List<Policy> policies, List<String> bucketKeys) {
        if (policies.size() != bucketKeys.size()) {
            throw new IllegalArgumentException("Policies and bucket keys must align");
        }
        if (policies.isEmpty()) {
            return List.of();
        }
        if (policies.size() == 1) {
            return List.of(evaluateOne(policies.getFirst(), bucketKeys.getFirst()));
        }
        Timer.Sample sample = Timer.start();
        try {
            List<Object> result = blockingAnd(policies, bucketKeys);
            sample.stop(metrics.redisOperationTimer());
            return parseAndResult(policies, result);
        } catch (RuntimeException ex) {
            sample.stop(metrics.redisOperationTimer());
            metrics.incrementRedisErrors();
            throw ex;
        }
    }

    public Mono<List<RateLimitDecision>> evaluateAllReactive(List<Policy> policies, List<String> bucketKeys) {
        if (policies.size() != bucketKeys.size()) {
            return Mono.error(new IllegalArgumentException("Policies and bucket keys must align"));
        }
        if (policies.isEmpty()) {
            return Mono.just(List.of());
        }
        if (policies.size() == 1) {
            return evaluateOneReactive(policies.getFirst(), bucketKeys.getFirst()).map(List::of);
        }
        Timer.Sample sample = Timer.start();
        return scriptResult(andScript, bucketKeys, andArgs(policies))
                .map(result -> parseAndResult(policies, result))
                .doOnError(error -> metrics.incrementRedisErrors())
                .doFinally(signal -> sample.stop(metrics.redisOperationTimer()));
    }

    private RateLimitDecision evaluateOne(Policy policy, String bucketKey) {
        Timer.Sample sample = Timer.start();
        try {
            List<Object> result = blockingOne(policy, bucketKey);
            sample.stop(metrics.redisOperationTimer());
            return parseOne(policy, result);
        } catch (RuntimeException ex) {
            sample.stop(metrics.redisOperationTimer());
            metrics.incrementRedisErrors();
            throw ex;
        }
    }

    private Mono<RateLimitDecision> evaluateOneReactive(Policy policy, String bucketKey) {
        Timer.Sample sample = Timer.start();
        TokenBucketConfiguration configuration = policy.strategy().configuration();
        List<String> args = List.of(
                String.valueOf(configuration.capacity()),
                String.valueOf(configuration.refillRate()),
                String.valueOf(configuration.refillPeriodMillis()),
                String.valueOf(System.currentTimeMillis()),
                "1"
        );
        return scriptResult(script, List.of(bucketKey), args)
                .map(result -> parseOne(policy, result))
                .doOnError(error -> metrics.incrementRedisErrors())
                .doFinally(signal -> sample.stop(metrics.redisOperationTimer()));
    }

    @SuppressWarnings("unchecked")
    private List<Object> blockingAnd(List<Policy> policies, List<String> bucketKeys) {
        List<String> args = andArgs(policies);
        return (List<Object>) redisTemplate.execute(andScript, bucketKeys, (Object[]) args.toArray(new String[0]));
    }

    @SuppressWarnings("unchecked")
    private List<Object> blockingOne(Policy policy, String bucketKey) {
        TokenBucketConfiguration configuration = policy.strategy().configuration();
        return (List<Object>) redisTemplate.execute(
                script,
                List.of(bucketKey),
                String.valueOf(configuration.capacity()),
                String.valueOf(configuration.refillRate()),
                String.valueOf(configuration.refillPeriodMillis()),
                String.valueOf(System.currentTimeMillis()),
                "1"
        );
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

    private static List<String> andArgs(List<Policy> policies) {
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(System.currentTimeMillis()));
        args.add("1");
        for (Policy policy : policies) {
            TokenBucketConfiguration configuration = policy.strategy().configuration();
            args.add(String.valueOf(configuration.capacity()));
            args.add(String.valueOf(configuration.refillRate()));
            args.add(String.valueOf(configuration.refillPeriodMillis()));
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
