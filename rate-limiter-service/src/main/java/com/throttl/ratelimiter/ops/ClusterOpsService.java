package com.throttl.ratelimiter.ops;

import com.throttl.ratelimiter.config.RateLimiterProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ClusterOpsService {

    private final RateLimiterProperties properties;
    private final MeterRegistry meterRegistry;
    private final RestClient restClient;

    public ClusterOpsService(RateLimiterProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(400));
        factory.setReadTimeout(Duration.ofMillis(800));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public Map<String, Object> selfSnapshot() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", properties.instanceId());
        body.put("status", "UP");
        body.put("checks", counter("rate_limit_checks_total"));
        body.put("allowed", counter("rate_limit_allowed_total"));
        body.put("rejected", taggedSum("rate_limit_rejected_total"));
        body.put("cacheHits", counter("policy_cache_hits_total"));
        body.put("cacheMisses", counter("policy_cache_misses_total"));
        body.put("p95LatencyMs", percentileMs("rate_limit_check_latency_seconds", 0.95));
        body.put("p99LatencyMs", percentileMs("rate_limit_check_latency_seconds", 0.99));
        body.put("redisP95Ms", percentileMs("redis_operation_latency_seconds", 0.95));
        return body;
    }

    public List<Map<String, Object>> instances() {
        List<Map<String, Object>> result = new ArrayList<>();
        if (properties.getInstances() == null || properties.getInstances().isEmpty()) {
            Map<String, Object> self = selfSnapshot();
            self.put("url", "local");
            result.add(self);
            return result;
        }
        for (RateLimiterProperties.PeerInstance peer : properties.getInstances()) {
            result.add(probePeer(peer));
        }
        return result;
    }

    public Map<String, Object> instance(String id) {
        return instances().stream()
                .filter(item -> id.equals(String.valueOf(item.get("id"))))
                .findFirst()
                .orElse(Map.of("id", id, "status", "UNKNOWN"));
    }

    public Map<String, Object> overview() {
        Map<String, Object> body = selfSnapshot();
        List<Map<String, Object>> peers = instances();
        long healthy = peers.stream().filter(p -> "UP".equals(p.get("status"))).count();
        body.put("healthyInstances", healthy);
        body.put("totalInstances", peers.size());
        body.put("instances", peers);
        body.put("cacheHitRate", hitRate(
                ((Number) body.get("cacheHits")).doubleValue(),
                ((Number) body.get("cacheMisses")).doubleValue()
        ));
        body.put("requestsPerSecond", requestsPerSecond());
        return body;
    }

    public Map<String, Object> analytics(String range) {
        Duration window = parseRange(range);
        Instant end = Instant.now();
        Instant start = end.minus(window);
        long step = Math.max(15, window.toSeconds() / 60);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("range", range);
        try {
            body.put("available", true);
            body.put("throughput", queryRange("sum(rate(rate_limit_checks_total[1m]))", start, end, step));
            body.put("allowed", queryRange("sum(rate(rate_limit_allowed_total[1m]))", start, end, step));
            body.put("rejected", queryRange("sum(rate(rate_limit_rejected_total[1m]))", start, end, step));
            body.put("latencyP50", queryRange(
                    "histogram_quantile(0.50, sum(rate(rate_limit_check_latency_seconds_bucket[5m])) by (le))",
                    start, end, step));
            body.put("latencyP95", queryRange(
                    "histogram_quantile(0.95, sum(rate(rate_limit_check_latency_seconds_bucket[5m])) by (le))",
                    start, end, step));
            body.put("latencyP99", queryRange(
                    "histogram_quantile(0.99, sum(rate(rate_limit_check_latency_seconds_bucket[5m])) by (le))",
                    start, end, step));
            body.put("redisP95", queryRange(
                    "histogram_quantile(0.95, sum(rate(redis_operation_latency_seconds_bucket[5m])) by (le))",
                    start, end, step));
            body.put("cacheHits", queryRange("sum(rate(policy_cache_hits_total[1m]))", start, end, step));
            body.put("cacheMisses", queryRange("sum(rate(policy_cache_misses_total[1m]))", start, end, step));
        } catch (Exception ex) {
            body.put("available", false);
            body.put("error", "Metrics backend unavailable");
        }
        return body;
    }

    private Map<String, Object> probePeer(RateLimiterProperties.PeerInstance peer) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", peer.getId());
        row.put("url", peer.getUrl());
        try {
            Map<String, Object> remote = restClient.get()
                    .uri(peer.getUrl() + "/v1/system/self")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (remote != null) {
                row.putAll(remote);
            }
            row.put("id", peer.getId());
            row.put("url", peer.getUrl());
            row.put("status", "UP");
        } catch (Exception ex) {
            row.put("status", "DOWN");
        }
        return row;
    }

    private List<Map<String, Object>> queryRange(String query, Instant start, Instant end, long stepSeconds) {
        Map<?, ?> payload = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("http")
                        .host(host(properties.prometheusUrl()))
                        .port(port(properties.prometheusUrl()))
                        .path("/api/v1/query_range")
                        .queryParam("query", query)
                        .queryParam("start", start.getEpochSecond())
                        .queryParam("end", end.getEpochSecond())
                        .queryParam("step", stepSeconds)
                        .build())
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> points = new ArrayList<>();
        if (payload == null) {
            return points;
        }
        Object data = payload.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            Object result = dataMap.get("result");
            if (result instanceof List<?> series && !series.isEmpty() && series.get(0) instanceof Map<?, ?> first) {
                Object values = first.get("values");
                if (values instanceof List<?> tuples) {
                    for (Object tuple : tuples) {
                        if (tuple instanceof List<?> pair && pair.size() >= 2) {
                            Map<String, Object> point = new LinkedHashMap<>();
                            point.put("t", pair.get(0));
                            point.put("v", Double.parseDouble(String.valueOf(pair.get(1))));
                            points.add(point);
                        }
                    }
                }
            }
        }
        return points;
    }

    private String host(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception ex) {
            return "localhost";
        }
    }

    private int port(String url) {
        try {
            int p = java.net.URI.create(url).getPort();
            return p > 0 ? p : 9090;
        } catch (Exception ex) {
            return 9090;
        }
    }

    private double counter(String name) {
        Counter counter = meterRegistry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    private double taggedSum(String name) {
        return meterRegistry.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    private double percentileMs(String timerName, double percentile) {
        Timer timer = meterRegistry.find(timerName).timer();
        if (timer == null) {
            return 0;
        }
        return timer.percentile(percentile, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private double hitRate(double hits, double misses) {
        double total = hits + misses;
        return total <= 0 ? 0 : hits / total;
    }

    private double requestsPerSecond() {
        double uptimeSec = Math.max(1.0, java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0);
        return counter("rate_limit_checks_total") / uptimeSec;
    }

    private Duration parseRange(String range) {
        if (range == null) {
            return Duration.ofHours(1);
        }
        return switch (range) {
            case "15m" -> Duration.ofMinutes(15);
            case "6h" -> Duration.ofHours(6);
            case "24h" -> Duration.ofHours(24);
            default -> Duration.ofHours(1);
        };
    }
}
