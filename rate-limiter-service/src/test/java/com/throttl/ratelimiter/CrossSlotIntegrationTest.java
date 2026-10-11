package com.throttl.ratelimiter;

import com.throttl.ratelimiter.api.dto.RateLimitCheckRequest;
import com.throttl.ratelimiter.domain.Algorithm;
import com.throttl.ratelimiter.domain.LimitingStrategy;
import com.throttl.ratelimiter.domain.Matching;
import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.domain.PolicyMetadata;
import com.throttl.ratelimiter.domain.RefillPeriod;
import com.throttl.ratelimiter.domain.Scope;
import com.throttl.ratelimiter.domain.TokenBucketConfiguration;
import com.throttl.ratelimiter.engine.PolicyCatalog;
import com.throttl.ratelimiter.engine.RateLimitDecision;
import com.throttl.ratelimiter.engine.RateLimitEngine;
import com.throttl.ratelimiter.store.PolicyStore;
import io.lettuce.core.cluster.SlotHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the Redis Cluster code path (per-slot scripts + refund) against a
 * standalone Redis. Slot grouping is computed client-side, so the protocol is
 * the same one a real cluster would see.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerIsAvailable")
@SpringBootTest
class CrossSlotIntegrationTest {

    private static final String USER_KEY = "rl:{USER:u-cross}:cross-user-limit";
    private static final String TENANT_KEY = "rl:{TENANT:acme}:cross-tenant-limit";
    private static final RateLimitCheckRequest REQUEST =
            new RateLimitCheckRequest("u-cross", "acme", "payment", null);

    static boolean dockerIsAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception ex) {
            return false;
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("throttl")
            .withUsername("throttl")
            .withPassword("throttl");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("throttl.redis-cluster", () -> true);
    }

    @Autowired
    private RateLimitEngine engine;

    @Autowired
    private PolicyStore policyStore;

    @Autowired
    private PolicyCatalog policyCatalog;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void resetState() {
        policyStore.findAll().forEach(policy -> policyStore.delete(policy.id()));
        try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
        policyCatalog.invalidate();
        assertThat(SlotHash.getSlot(USER_KEY))
                .as("test premise: user and tenant buckets live on different slots")
                .isNotEqualTo(SlotHash.getSlot(TENANT_KEY));
    }

    @Test
    void denialOnOneSlotRefundsTheOtherSlot() {
        createPolicy("cross-user-limit", Scope.USER, 100);
        createPolicy("cross-tenant-limit", Scope.TENANT, 1);

        assertThat(engine.check(REQUEST).allowed()).isTrue();
        assertThat(tokens(USER_KEY)).isEqualTo(99);
        assertThat(tokens(TENANT_KEY)).isZero();

        RateLimitDecision denied = engine.check(REQUEST);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.rejectedBy()).isEqualTo("cross-tenant-limit");
        assertThat(tokens(USER_KEY))
                .as("user slot consumed before the tenant slot denied, then was refunded")
                .isEqualTo(99);
    }

    @Test
    void reactivePathRefundsTheOtherSlot() {
        createPolicy("cross-user-limit", Scope.USER, 100);
        createPolicy("cross-tenant-limit", Scope.TENANT, 1);

        assertThat(engine.checkReactive(REQUEST).block().allowed()).isTrue();
        RateLimitDecision denied = engine.checkReactive(REQUEST).block();

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.rejectedBy()).isEqualTo("cross-tenant-limit");
        assertThat(tokens(USER_KEY)).isEqualTo(99);
    }

    @Test
    void concurrentCrossSlotChecksNeverOverspendAndConserveTokens() throws Exception {
        createPolicy("cross-user-limit", Scope.USER, 100);
        createPolicy("cross-tenant-limit", Scope.TENANT, 50);

        int total = 1000;
        AtomicInteger allowed = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            tasks.add(() -> {
                if (engine.check(REQUEST).allowed()) {
                    allowed.incrementAndGet();
                }
                return null;
            });
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Void> future : executor.invokeAll(tasks)) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(allowed.get()).isBetween(1, 50);
        assertThat(tokens(USER_KEY))
                .as("every refunded consume is returned; only allowed checks stay spent")
                .isEqualTo(100 - allowed.get());
        assertThat(tokens(TENANT_KEY)).isEqualTo(50 - allowed.get());
    }

    private long tokens(String key) {
        Object value = redisTemplate.opsForHash().get(key, "tokens");
        return value == null ? -1 : Long.parseLong(value.toString());
    }

    private void createPolicy(String id, Scope scope, long capacity) {
        policyStore.create(new Policy(
                id,
                id,
                new Matching(scope, Map.of("api", "payment")),
                new LimitingStrategy(Algorithm.TOKEN_BUCKET, new TokenBucketConfiguration(capacity, 1, RefillPeriod.HOUR)),
                new PolicyMetadata(true, 10),
                null,
                null
        ));
        policyCatalog.invalidate();
    }
}
