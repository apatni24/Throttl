package com.throttl.ratelimiter;

import com.throttl.ratelimiter.api.dto.RateLimitCheckRequest;
import com.throttl.ratelimiter.engine.PolicyCatalog;
import com.throttl.ratelimiter.engine.RateLimitDecision;
import com.throttl.ratelimiter.engine.RateLimitEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.DockerClientFactory;

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

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerIsAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimiterIntegrationTest {

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
        registry.add("throttl.cache-ttl-seconds", () -> 60);
        registry.add("throttl.fail-closed", () -> true);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RateLimitEngine engine;

    @Autowired
    private PolicyCatalog policyCatalog;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void resetState() {
        ResponseEntity<Map[]> existing = restTemplate.getForEntity("/v1/policies", Map[].class);
        if (existing.getBody() != null) {
            for (Map policy : existing.getBody()) {
                restTemplate.delete("/v1/policies/" + policy.get("id"));
            }
        }
        try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
        policyCatalog.invalidate();
    }

    @Test
    void healthIsUp() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    void prometheusEndpointIsExposed() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_");
    }

    @Test
    void policyCrudRoundTrip() {
        String body = policyJson("payment-user-limit", "USER", 100, 10, "SECOND", "payment");
        ResponseEntity<Map> created = restTemplate.postForEntity("/v1/policies", json(body), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("id", "payment-user-limit");

        ResponseEntity<Map> fetched = restTemplate.getForEntity("/v1/policies/payment-user-limit", Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);

        String updated = policyJson("payment-user-limit", "USER", 50, 5, "SECOND", "payment");
        restTemplate.put("/v1/policies/payment-user-limit", json(updated));
        Map afterUpdate = restTemplate.getForObject("/v1/policies/payment-user-limit", Map.class);
        Map strategy = (Map) afterUpdate.get("strategy");
        Map configuration = (Map) strategy.get("configuration");
        assertThat(((Number) configuration.get("capacity")).intValue()).isEqualTo(50);

        restTemplate.delete("/v1/policies/payment-user-limit");
        ResponseEntity<Map> missing = restTemplate.getForEntity("/v1/policies/payment-user-limit", Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void duplicatePolicyIdIsRejected() {
        String body = policyJson("payment-user-limit", "USER", 100, 10, "SECOND", "payment");
        assertThat(restTemplate.postForEntity("/v1/policies", json(body), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> duplicate = restTemplate.postForEntity("/v1/policies", json(body), Map.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(duplicate.getBody().get("message"))).contains("Duplicate");
    }

    @Test
    void tokenBucketAllowsThenRejects() {
        createPolicy("payment-user-limit", "USER", 5, 1, "MINUTE", "payment");
        RateLimitCheckRequest request = new RateLimitCheckRequest("u123", "acme", "payment", "10.0.0.1");

        int allowed = 0;
        RateLimitDecision last = null;
        for (int i = 0; i < 6; i++) {
            last = engine.check(request);
            if (last.allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(5);
        assertThat(last.allowed()).isFalse();
        assertThat(last.rejectedBy()).isEqualTo("payment-user-limit");
        assertThat(last.retryAfterMs()).isGreaterThan(0);
    }

    @Test
    void multiplePoliciesMustAllPass() {
        createPolicy("payment-user-limit", "USER", 100, 10, "MINUTE", "payment");
        createPolicy("payment-tenant-limit", "TENANT", 1, 1, "MINUTE", "payment");
        createPolicy("payment-api-limit", "API", 100, 10, "MINUTE", "payment");

        RateLimitCheckRequest request = new RateLimitCheckRequest("u123", "acme", "payment", "10.0.0.1");
        assertThat(engine.check(request).allowed()).isTrue();
        RateLimitDecision denied = engine.check(request);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.rejectedBy()).isEqualTo("payment-tenant-limit");
    }

    @Test
    void concurrentRequestsDoNotDoubleSpendTokens() throws Exception {
        createPolicy("payment-user-limit", "USER", 100, 1, "HOUR", "payment");
        RateLimitCheckRequest request = new RateLimitCheckRequest("user-123", "acme", "payment", "10.0.0.1");

        int total = 1000;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger allowed = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            tasks.add(() -> {
                if (engine.check(request).allowed()) {
                    allowed.incrementAndGet();
                }
                return null;
            });
        }
        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.close();

        assertThat(allowed.get()).isEqualTo(100);
    }

    @Test
    void allScopesCanBeCheckedIndependently() {
        createPolicy("user-limit", "USER", 1, 1, "HOUR", null);
        createPolicy("tenant-limit", "TENANT", 1, 1, "HOUR", null);
        createPolicy("api-limit", "API", 1, 1, "HOUR", null);
        createPolicy("ip-limit", "IP", 1, 1, "HOUR", null);

        assertThat(engine.check(new RateLimitCheckRequest("u1", null, null, null)).allowed()).isTrue();
        assertThat(engine.check(new RateLimitCheckRequest("u1", null, null, null)).allowed()).isFalse();

        assertThat(engine.check(new RateLimitCheckRequest(null, "t1", null, null)).allowed()).isTrue();
        assertThat(engine.check(new RateLimitCheckRequest(null, "t1", null, null)).allowed()).isFalse();

        assertThat(engine.check(new RateLimitCheckRequest(null, null, "a1", null)).allowed()).isTrue();
        assertThat(engine.check(new RateLimitCheckRequest(null, null, "a1", null)).allowed()).isFalse();

        assertThat(engine.check(new RateLimitCheckRequest(null, null, null, "1.1.1.1")).allowed()).isTrue();
        assertThat(engine.check(new RateLimitCheckRequest(null, null, null, "1.1.1.1")).allowed()).isFalse();
    }

    private void createPolicy(String id, String scope, long capacity, long refillRate, String period, String api) {
        String conditions = api == null ? "{}" : "{\"api\":\"" + api + "\"}";
        String body = """
                {
                  "id": "%s",
                  "name": "%s",
                  "matching": { "scope": "%s", "conditions": %s },
                  "strategy": {
                    "algorithm": "TOKEN_BUCKET",
                    "configuration": { "capacity": %d, "refillRate": %d, "refillPeriod": "%s" }
                  },
                  "metadata": { "enabled": true, "priority": 10 }
                }
                """.formatted(id, id, scope, conditions, capacity, refillRate, period);
        ResponseEntity<Map> created = restTemplate.postForEntity("/v1/policies", json(body), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private static String policyJson(String id, String scope, long capacity, long refillRate, String period, String api) {
        return """
                {
                  "id": "%s",
                  "name": "%s",
                  "matching": { "scope": "%s", "conditions": { "api": "%s" } },
                  "strategy": {
                    "algorithm": "TOKEN_BUCKET",
                    "configuration": { "capacity": %d, "refillRate": %d, "refillPeriod": "%s" }
                  },
                  "metadata": { "enabled": true, "priority": 10 }
                }
                """.formatted(id, id, scope, api, capacity, refillRate, period);
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
