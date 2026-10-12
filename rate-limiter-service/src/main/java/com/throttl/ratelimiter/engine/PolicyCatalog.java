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
    private final RedisCircuitBreaker breaker;
    private final List<Runnable> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public PolicyCatalog(
            PolicyStore policyStore,
            RateLimitMetrics metrics,
            com.throttl.ratelimiter.config.RateLimiterProperties properties,
            StringRedisTemplate redisTemplate,
            ReactiveStringRedisTemplate reactiveRedisTemplate,
            RedisCircuitBreaker breaker
    ) {
        this.policyStore = policyStore;
        this.breaker = breaker;
        this.metrics = metrics;
        this.redisTemplate = redisTemplate;
        this.reactiveRedisTemplate = reactiveRedisTemplate;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(properties.cacheTtlSeconds()))
                .maximumSize(16)
                .build();
    }

    /**
     * The generation read is a freshness check, not a dependency: if Redis is
     * unreachable the cached list is served (until its TTL), so the policies'
     * own failure modes decide instead of the whole catalog failing.
     */
    public List<Policy> enabledPolicies() {
        return applyGeneration(generationOrLastKnown());
    }

    public Mono<List<Policy>> enabledPoliciesAsync() {
        Mono<Long> generation = breaker.allowRequest()
                ? currentGenerationAsync()
                        .doOnSuccess(value -> breaker.onSuccess())
                        .onErrorResume(RuntimeException.class, ex -> {
                            breaker.onFailure(ex);
                            return Mono.just(localGeneration.get());
                        })
                : Mono.just(localGeneration.get());
        return generation.flatMap(remote -> {
            observeGeneration(remote);
            List<Policy> cached = cache.getIfPresent(ENABLED_KEY);
            if (cached != null) {
                metrics.incrementCacheHits();
                log.debug("Policy cache HIT");
                return Mono.just(cached);
            }
            return Mono.fromCallable(() -> loadFromStore())
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    /** Runs whenever this instance learns that policies changed (any instance's write). */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private void observeGeneration(long remote) {
        long previous = localGeneration.getAndSet(remote);
        if (previous != remote) {
            cache.invalidateAll();
            changeListeners.forEach(Runnable::run);
        }
    }

    private List<Policy> applyGeneration(long remote) {
        observeGeneration(remote);
        List<Policy> cached = cache.getIfPresent(ENABLED_KEY);
        if (cached != null) {
            metrics.incrementCacheHits();
            log.debug("Policy cache HIT");
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
        observeGeneration(currentGeneration());
    }

    private long generationOrLastKnown() {
        if (!breaker.allowRequest()) {
            return localGeneration.get();
        }
        try {
            long generation = currentGeneration();
            breaker.onSuccess();
            return generation;
        } catch (RuntimeException ex) {
            breaker.onFailure(ex);
            return localGeneration.get();
        }
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
