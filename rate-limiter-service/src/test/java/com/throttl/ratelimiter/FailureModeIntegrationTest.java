package com.throttl.ratelimiter;

import com.throttl.ratelimiter.api.dto.RateLimitCheckRequest;
import com.throttl.ratelimiter.domain.Algorithm;
import com.throttl.ratelimiter.domain.FailureMode;
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
import com.throttl.ratelimiter.engine.RedisCircuitBreaker;
import com.throttl.ratelimiter.store.PolicyStore;
import org.junit.jupiter.api.AfterEach;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes a real Redis (docker pause: connections stay open, nothing answers,
 * which is what a hung or partitioned node looks like) and checks that each
 * policy's failure mode decides, quickly, and that the service recovers.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerIsAvailable")
@SpringBootTest
class FailureModeIntegrationTest {

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
        registry.add("spring.data.redis.timeout", () -> "200ms");
        registry.add("throttl.redis-breaker-open-ms", () -> 300);
        registry.add("throttl.fail-closed", () -> true);
    }

    private static final RateLimitCheckRequest PAYMENT = new RateLimitCheckRequest("u1", "acme", "payment", null);
    private static final RateLimitCheckRequest SEARCH = new RateLimitCheckRequest("u1", "acme", "search", null);
    private static final RateLimitCheckRequest FEED = new RateLimitCheckRequest("u1", "acme", "feed", null);

    @Autowired
    private RateLimitEngine engine;

    @Autowired
    private PolicyStore policyStore;

    @Autowired
    private PolicyCatalog policyCatalog;

    @Autowired
    private RedisCircuitBreaker breaker;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private boolean paused;

    @BeforeEach
    void setUp() {
        policyStore.findAll().forEach(policy -> policyStore.delete(policy.id()));
        try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
        create("payment-limit", "payment", null);
        create("search-limit", "search", FailureMode.FAIL_OPEN);
        create("feed-limit", "feed", FailureMode.LOCAL_FALLBACK);
        policyCatalog.invalidate();
        assertThat(engine.check(PAYMENT).degraded()).as("warm the catalog while Redis is up").isFalse();
    }

    @AfterEach
    void resumeRedis() {
        if (paused) {
            DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
            paused = false;
        }
    }

    @Test
    void eachPolicyFollowsItsOwnFailureModeAndServiceRecovers() throws Exception {
        pauseRedis();

        RateLimitDecision payment = engine.check(PAYMENT);
        assertThat(payment.degraded()).isTrue();
        assertThat(payment.allowed()).as("no explicit mode -> service default FAIL_CLOSED").isFalse();
        assertThat(payment.rejectedBy()).isEqualTo("redis-unavailable");
        assertThat(breaker.isOpen()).isTrue();

        RateLimitDecision search = engine.check(SEARCH);
        assertThat(search.degraded()).isTrue();
        assertThat(search.allowed()).isTrue();

        // capacity 30 / 3 configured instances = 10 per instance
        int feedAllowed = 0;
        for (int i = 0; i < 15; i++) {
            if (engine.checkReactive(FEED).block().allowed()) {
                feedAllowed++;
            }
        }
        assertThat(feedAllowed).isEqualTo(10);

        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            engine.check(SEARCH);
        }
        long avgMicros = (System.nanoTime() - start) / 1_000 / 200;
        assertThat(avgMicros)
                .as("breaker open: checks degrade without waiting for the 200ms Redis timeout")
                .isLessThan(20_000);

        resumeRedis();
        long deadline = System.currentTimeMillis() + 10_000;
        RateLimitDecision recovered = engine.check(PAYMENT);
        while (recovered.degraded() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            recovered = engine.check(PAYMENT);
        }
        assertThat(recovered.degraded()).as("Redis is back; decisions come from Redis again").isFalse();
        assertThat(recovered.allowed()).isTrue();
        assertThat(breaker.isOpen()).isFalse();
    }

    private void pauseRedis() {
        DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
        paused = true;
    }

    private void create(String id, String api, FailureMode mode) {
        policyStore.create(new Policy(
                id,
                id,
                new Matching(Scope.USER, Map.of("api", api)),
                new LimitingStrategy(Algorithm.TOKEN_BUCKET, new TokenBucketConfiguration(30, 1, RefillPeriod.HOUR)),
                new PolicyMetadata(true, 10, mode),
                null,
                null
        ));
    }
}
