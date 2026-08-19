package com.throttl.ratelimiter.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Runtime token-bucket keys live in Redis as {@code rl:{policyId}:{scopeValue}}.
 * Policy configuration changes are incompatible with leftover token counts,
 * so we delete every bucket for that policy on update or delete.
 */
@Component
public class RuntimeBucketStore {

    private static final Logger log = LoggerFactory.getLogger(RuntimeBucketStore.class);

    private final StringRedisTemplate redisTemplate;

    public RuntimeBucketStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void deleteBucketsForPolicy(String policyId) {
        String pattern = "rl:" + policyId + ":*";
        int deleted = 0;
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(256).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                redisTemplate.delete(cursor.next());
                deleted++;
            }
        }
        log.info("Reset Redis buckets for policy {} (deleted {} keys matching {})", policyId, deleted, pattern);
    }
}
