package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.metrics.RateLimitMetrics;
import com.throttl.ratelimiter.store.PolicyStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    private final ReactiveStringRedisTemplate reactiveRedisTemplate;
    private final Cache<String, List<Policy>> cache;
    private final AtomicLong localGeneration = new AtomicLong(Long.MIN_VALUE);

    public PolicyCatalog(
            PolicyStore policyStore,
            RateLimitMetrics metrics,
            com.throttl.ratelimiter.config.RateLimiterProperties properties,
            StringRedisTemplate redisTemplate,
            ReactiveStringRedisTemplate reactiveRedisTemplate
    ) {
        this.policyStore = policyStore;
        this.metrics = metrics;
        this.redisTemplate = redisTemplate;
        this.reactiveRedisTemplate = reactiveRedisTemplate;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(properties.cacheTtlSeconds()))
                .maximumSize(16)
                .build();
    }

    public List<Policy> enabledPolicies() {
        return applyGeneration(currentGeneration());
    }

    public Mono<List<Policy>> enabledPoliciesAsync() {
        return currentGenerationAsync().flatMap(remote -> {
            if (localGeneration.get() != remote) {
                cache.invalidateAll();
                localGeneration.set(remote);
            }
            List<Policy> cached = cache.getIfPresent(ENABLED_KEY);
            if (cached != null) {
                metrics.incrementCacheHits();
                log.info("Policy cache HIT");
                return Mono.just(cached);
            }
            return Mono.fromCallable(() -> loadFromStore())
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    private List<Policy> applyGeneration(long remote) {
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
        return loadFromStore();
    }

    private List<Policy> loadFromStore() {
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
        Timer.Sample sample = Timer.start();
        try {
            return parseGeneration(redisTemplate.opsForValue().get(GENERATION_KEY));
        } finally {
            sample.stop(metrics.generationGetTimer());
        }
    }

    private Mono<Long> currentGenerationAsync() {
        Timer.Sample sample = Timer.start();
        return reactiveRedisTemplate.opsForValue().get(GENERATION_KEY)
                .map(this::parseGeneration)
                .defaultIfEmpty(0L)
                .doFinally(signal -> sample.stop(metrics.generationGetTimer()));
    }

    private long parseGeneration(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }
}
