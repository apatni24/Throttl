package com.throttl.ratelimiter.engine;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Intentionally racy token consume used only to show why Lua is required.
 * Do not use this on the request path.
 */
final class NaiveRedisTokenBucket {

    private final StringRedisTemplate redis;

    NaiveRedisTokenBucket(StringRedisTemplate redis) {
        this.redis = redis;
    }

    boolean consume(String key, long capacity) {
        String raw = (String) redis.opsForHash().get(key, "tokens");
        long tokens = raw == null ? capacity : Long.parseLong(raw);
        if (tokens <= 0) {
            return false;
        }
        redis.opsForHash().put(key, "tokens", String.valueOf(tokens - 1));
        return true;
    }
}
