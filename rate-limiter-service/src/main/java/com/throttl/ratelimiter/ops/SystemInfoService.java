package com.throttl.ratelimiter.ops;

import com.throttl.ratelimiter.config.RateLimiterProperties;
import com.throttl.ratelimiter.config.SentinelFailoverWatcher;
import com.throttl.ratelimiter.engine.RedisCircuitBreaker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.SystemHealth;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SystemInfoService {

    private final RateLimiterProperties properties;
    private final HealthEndpoint healthEndpoint;
    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;
    private final RedisCircuitBreaker redisCircuitBreaker;
    private final ObjectProvider<SentinelFailoverWatcher> sentinelWatcher;
    private final RestClient restClient = RestClient.create();

    public SystemInfoService(
            RateLimiterProperties properties,
            HealthEndpoint healthEndpoint,
            DataSource dataSource,
            RedisConnectionFactory redisConnectionFactory,
            RedisCircuitBreaker redisCircuitBreaker,
            ObjectProvider<SentinelFailoverWatcher> sentinelWatcher
    ) {
        this.properties = properties;
        this.redisCircuitBreaker = redisCircuitBreaker;
        this.sentinelWatcher = sentinelWatcher;
        this.healthEndpoint = healthEndpoint;
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    public Map<String, Object> health() {
        HealthComponent component = healthEndpoint.health();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", component.getStatus().getCode());
        if (component instanceof SystemHealth systemHealth) {
            Map<String, String> parts = new LinkedHashMap<>();
            systemHealth.getComponents().forEach((name, value) ->
                    parts.put(name, value.getStatus().getCode()));
            body.put("components", parts);
        }
        body.put("prometheus", probe(properties.prometheusUrl() + "/-/healthy"));
        body.put("grafana", probe(properties.grafanaUrl() + "/api/health"));
        return body;
    }

    public Map<String, Object> info() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application", "rate-limiter-service");
        body.put("version", "0.1.0");
        body.put("environment", properties.environment());
        body.put("instanceId", properties.instanceId());
        body.put("javaVersion", System.getProperty("java.version"));
        body.put("springBootVersion", SpringBootVersion.getVersion());
        body.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        body.put("cacheTtlSeconds", properties.cacheTtlSeconds());
        body.put("failClosed", properties.failClosed());
        body.put("defaultFailureMode", properties.failClosed() ? "FAIL_CLOSED" : "FAIL_OPEN");
        body.put("redisCircuitOpen", redisCircuitBreaker.isOpen());
        body.put("redisTimeoutMs", 500);
        body.put("configuredInstances", properties.getInstances().size());
        body.put("redisVersion", redisVersion());
        body.put("redisTopology", redisTopology());
        sentinelWatcher.ifAvailable(watcher ->
                watcher.currentPrimary().ifPresent(primary -> body.put("redisPrimary", primary)));
        body.put("postgresVersion", postgresVersion());
        return body;
    }

    private Map<String, Object> probe(String url) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            restClient.get().uri(url).retrieve().toBodilessEntity();
            result.put("status", "UP");
        } catch (Exception ex) {
            result.put("status", "DOWN");
        }
        return result;
    }

    private String redisVersion() {
        try (var connection = redisConnectionFactory.getConnection()) {
            Object info = connection.serverCommands().info("server");
            if (info instanceof java.util.Properties props) {
                return props.getProperty("redis_version", "unknown");
            }
        } catch (Exception ignored) {
            return "unavailable";
        }
        return "unknown";
    }

    private String redisTopology() {
        if (sentinelWatcher.getIfAvailable() != null) {
            return "SENTINEL";
        }
        return properties.redisCluster() ? "CLUSTER" : "STANDALONE";
    }

    private String postgresVersion() {
        try {
            return new JdbcTemplate(dataSource).queryForObject("show server_version", String.class);
        } catch (Exception ex) {
            return "unavailable";
        }
    }

    public Duration probeTimeout() {
        return Duration.ofMillis(400);
    }
}
