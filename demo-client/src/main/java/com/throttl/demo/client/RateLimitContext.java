package com.throttl.demo.client;

public record RateLimitContext(String userId, String tenantId, String api, String ip) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String userId;
        private String tenantId;
        private String api;
        private String ip;

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder api(String api) {
            this.api = api;
            return this;
        }

        public Builder ip(String ip) {
            this.ip = ip;
            return this;
        }

        public RateLimitContext build() {
            return new RateLimitContext(userId, tenantId, api, ip);
        }
    }
}
