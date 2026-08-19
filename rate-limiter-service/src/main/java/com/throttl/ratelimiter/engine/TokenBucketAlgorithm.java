package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.domain.TokenBucketConfiguration;
import com.throttl.ratelimiter.metrics.RateLimitMetrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TokenBucketAlgorithm implements RateLimitAlgorithm {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> script;
    private final RateLimitMetrics metrics;

    public TokenBucketAlgorithm(StringRedisTemplate redisTemplate, RateLimitMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
        this.script = new DefaultRedisScript<>();
        this.script.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/token_bucket.lua")));
        this.script.setResultType(List.class);
    }

    @Override
    public RateLimitDecision evaluate(Policy policy, String bucketKey) {
        TokenBucketConfiguration configuration = policy.strategy().configuration();
        Timer.Sample sample = Timer.start();
        try {
            @SuppressWarnings("unchecked")
            List<Object> result = (List<Object>) redisTemplate.execute(
                    script,
                    List.of(bucketKey),
                    String.valueOf(configuration.capacity()),
                    String.valueOf(configuration.refillRate()),
                    String.valueOf(configuration.refillPeriodMillis()),
                    String.valueOf(System.currentTimeMillis()),
                    "1"
            );
            sample.stop(metrics.redisOperationTimer());
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
        } catch (RuntimeException ex) {
            sample.stop(metrics.redisOperationTimer());
            metrics.incrementRedisErrors();
            throw ex;
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Unexpected Lua value: " + value);
    }
}
