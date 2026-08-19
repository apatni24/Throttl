package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.metrics.RateLimitMetrics;
import com.throttl.ratelimiter.store.PolicyStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class PolicyCatalog {

    static final String ENABLED_KEY = "enabled";
    static final String GENERATION_KEY = "policy:generation";

    private static final Logger log = LoggerFactory.getLogger(PolicyCatalog.class);

    private final PolicyStore policyStore;
    private final RateLimitMetrics metrics;
    private final StringRedisTemplate redisTemplate;
    private final Cache<String, List<Policy>> cache;
    private final AtomicLong localGeneration = new AtomicLong(Long.MIN_VALUE);

    public PolicyCatalog(
            PolicyStore policyStore,
            RateLimitMetrics metrics,
            com.throttl.ratelimiter.config.RateLimiterProperties properties,
            StringRedisTemplate redisTemplate
    ) {
        this.policyStore = policyStore;
        this.metrics = metrics;
        this.redisTemplate = redisTemplate;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(properties.cacheTtlSeconds()))
                .maximumSize(16)
                .build();
    }

    public List<Policy> enabledPolicies() {
        long remote = currentGeneration();
        if (localGeneration.get() != remote) {
            cache.invalidateAll();
            localGeneration.set(remote);
        }
        List<Policy> cached = cache.getIfPresent(ENABLED_KEY);
        if (cached != null) {
            metrics.incrementCacheHits();
            log.info("Policy cache HIT");
            return cached;
        }
        metrics.incrementCacheMisses();
        log.info("Policy cache MISS -> PostgreSQL");
        List<Policy> loaded = policyStore.findEnabled();
        cache.put(ENABLED_KEY, loaded);
        return loaded;
    }

    public void invalidate() {
        redisTemplate.opsForValue().increment(GENERATION_KEY);
        cache.invalidateAll();
        localGeneration.set(currentGeneration());
    }

    private long currentGeneration() {
        String value = redisTemplate.opsForValue().get(GENERATION_KEY);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }
}
