package com.throttl.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "throttl")
public class RateLimiterProperties {

    private long cacheTtlSeconds = 60;
    /** Default for policies without an explicit failure mode: true = FAIL_CLOSED, false = FAIL_OPEN. */
    private boolean failClosed = true;
    /** How long Redis calls are skipped after a failure before one probe is let through. */
    private long redisBreakerOpenMs = 1000;
    private String instanceId = "local";
    private String environment = "LOCAL";
    private List<PeerInstance> instances = new ArrayList<>();
    private String prometheusUrl = "http://localhost:9090";
    private String grafanaUrl = "http://localhost:3000";
    private String corsOrigins = "http://localhost:5173,http://localhost:4173";
    /** sync = Tomcat waits on Redis; async = Lettuce reactive completion (same Lua). */
    private String checkIo = "sync";
    /**
     * Lettuce default: one shared native connection multiplexes all commands.
     * false + pool enabled = dedicated connections from a GenericObjectPool.
     */
    private boolean redisShareNativeConnection = false;
    /** true = bucket keys may live on different hash slots; evaluate per slot and refund on deny. */
    private boolean redisCluster = false;
    /** Bearer token for control-plane writes. Blank = writes are rejected. */
    private String adminToken = "";

    public long cacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public boolean failClosed() {
        return failClosed;
    }

    public void setFailClosed(boolean failClosed) {
        this.failClosed = failClosed;
    }

    public long redisBreakerOpenMs() {
        return redisBreakerOpenMs;
    }

    public void setRedisBreakerOpenMs(long redisBreakerOpenMs) {
        this.redisBreakerOpenMs = Math.max(0, redisBreakerOpenMs);
    }

    public String instanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String environment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public List<PeerInstance> getInstances() {
        return instances;
    }

    public void setInstances(List<PeerInstance> instances) {
        this.instances = instances == null ? new ArrayList<>() : instances;
    }

    public String prometheusUrl() {
        return prometheusUrl;
    }

    public void setPrometheusUrl(String prometheusUrl) {
        this.prometheusUrl = prometheusUrl;
    }

    public String grafanaUrl() {
        return grafanaUrl;
    }

    public void setGrafanaUrl(String grafanaUrl) {
        this.grafanaUrl = grafanaUrl;
    }

    public String corsOrigins() {
        return corsOrigins;
    }

    public void setCorsOrigins(String corsOrigins) {
        this.corsOrigins = corsOrigins;
    }

    public String checkIo() {
        return checkIo;
    }

    public void setCheckIo(String checkIo) {
        this.checkIo = checkIo == null || checkIo.isBlank() ? "sync" : checkIo;
    }

    public boolean asyncCheck() {
        return "async".equalsIgnoreCase(checkIo);
    }

    public boolean redisShareNativeConnection() {
        return redisShareNativeConnection;
    }

    public void setRedisShareNativeConnection(boolean redisShareNativeConnection) {
        this.redisShareNativeConnection = redisShareNativeConnection;
    }

    public boolean redisCluster() {
        return redisCluster;
    }

    public void setRedisCluster(boolean redisCluster) {
        this.redisCluster = redisCluster;
    }

    public String adminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    public static class PeerInstance {
        private String id;
        private String url;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}
