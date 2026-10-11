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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
        registry.add("throttl.admin-token", () -> ADMIN_TOKEN);
    }

    private static final String ADMIN_TOKEN = "test-admin-token";

    @LocalServerPort
    private int port;

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
        List<ClientHttpRequestInterceptor> interceptors = restTemplate.getRestTemplate().getInterceptors();
        if (interceptors.stream().noneMatch(AdminAuth.class::isInstance)) {
            interceptors.add(new AdminAuth());
        }
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
    void reactiveTokenBucketAllowsThenRejects() {
        createPolicy("payment-user-limit", "USER", 5, 1, "MINUTE", "payment");
        RateLimitCheckRequest request = new RateLimitCheckRequest("u-reactive", "acme", "payment", "10.0.0.1");

        int allowed = 0;
        RateLimitDecision last = null;
        for (int i = 0; i < 6; i++) {
            last = engine.checkReactive(request).block();
            if (last.allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(5);
        assertThat(last.allowed()).isFalse();
        assertThat(last.rejectedBy()).isEqualTo("payment-user-limit");
    }

    @Test
    void multiplePoliciesMustAllPass() {
        createPolicy("payment-user-limit", "USER", 100, 10, "MINUTE", "payment");
        createPolicy("payment-tenant-limit", "TENANT", 1, 1, "MINUTE", "payment");
        createPolicy("payment-api-limit", "API", 100, 10, "MINUTE", "payment");

        RateLimitCheckRequest request = new RateLimitCheckRequest("u123", "acme", "payment", "10.0.0.1");
        assertThat(engine.check(request).allowed()).isTrue();
        assertThat(redisTemplate.opsForHash().get("rl:{USER:u123}:payment-user-limit", "tokens")).isEqualTo("99");

        RateLimitDecision denied = engine.check(request);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.rejectedBy()).isEqualTo("payment-tenant-limit");
        assertThat(redisTemplate.opsForHash().get("rl:{USER:u123}:payment-user-limit", "tokens"))
                .as("a later policy reject must not consume tokens from earlier policies")
                .isEqualTo("99");
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

    @Test
    void controlPlaneWritesRequireAdminToken() throws Exception {
        String body = policyJson("payment-user-limit", "USER", 100, 10, "SECOND", "payment");
        assertThat(rawPost("/v1/policies", body, null).statusCode()).isEqualTo(401);
        assertThat(rawPost("/v1/policies", body, "wrong-token").statusCode()).isEqualTo(401);
        assertThat(rawPost("/v1/playground/burst", "{\"count\":1}", null).statusCode()).isEqualTo(401);
        assertThat(restTemplate.getForEntity("/v1/policies/payment-user-limit", Map.class).getStatusCode())
                .as("rejected writes must not reach the store")
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(rawPost("/v1/policies", body, ADMIN_TOKEN).statusCode()).isEqualTo(201);
    }

    @Test
    void readsAndChecksStayOpenWithoutToken() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> list = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/policies")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(rawPost("/v1/check", "{\"userId\":\"u1\",\"api\":\"payment\"}", null).statusCode()).isEqualTo(200);
    }

    @Test
    void policyUpdateDeletesThatPolicysBucketsOnly() {
        createPolicy("payment-user-limit", "USER", 5, 1, "HOUR", "payment");
        createPolicy("payment-tenant-limit", "TENANT", 5, 1, "HOUR", "payment");
        engine.check(new RateLimitCheckRequest("u-reset", "acme", "payment", null));
        assertThat(redisTemplate.hasKey("rl:{USER:u-reset}:payment-user-limit")).isTrue();
        assertThat(redisTemplate.hasKey("rl:{TENANT:acme}:payment-tenant-limit")).isTrue();

        restTemplate.put("/v1/policies/payment-user-limit",
                json(policyJson("payment-user-limit", "USER", 10, 1, "HOUR", "payment")));

        assertThat(redisTemplate.hasKey("rl:{USER:u-reset}:payment-user-limit")).isFalse();
        assertThat(redisTemplate.hasKey("rl:{TENANT:acme}:payment-tenant-limit")).isTrue();
    }

    private HttpResponse<String> rawPost(String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private static final class AdminAuth implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(
                org.springframework.http.HttpRequest request,
                byte[] body,
                ClientHttpRequestExecution execution
        )
                throws IOException {
            if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                request.getHeaders().setBearerAuth(ADMIN_TOKEN);
            }
            return execution.execute(request, body);
        }
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
