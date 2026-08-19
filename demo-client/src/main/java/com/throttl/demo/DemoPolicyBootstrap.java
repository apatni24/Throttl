package com.throttl.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DemoPolicyBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoPolicyBootstrap.class);

    private final RestClient restClient;

    public DemoPolicyBootstrap(@Value("${rate-limiter.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public void run(ApplicationArguments args) {
        upsert("""
                {
                  "id": "payment-user-limit",
                  "name": "Payment User Limit",
                  "matching": { "scope": "USER", "conditions": { "api": "payment" } },
                  "strategy": {
                    "algorithm": "TOKEN_BUCKET",
                    "configuration": { "capacity": 100, "refillRate": 100, "refillPeriod": "MINUTE" }
                  },
                  "metadata": { "enabled": true, "priority": 10 }
                }
                """);
        upsert("""
                {
                  "id": "payment-tenant-limit",
                  "name": "Payment Tenant Limit",
                  "matching": { "scope": "TENANT", "conditions": { "api": "payment" } },
                  "strategy": {
                    "algorithm": "TOKEN_BUCKET",
                    "configuration": { "capacity": 10000, "refillRate": 10000, "refillPeriod": "MINUTE" }
                  },
                  "metadata": { "enabled": true, "priority": 20 }
                }
                """);
    }

    private void upsert(String body) {
        try {
            restClient.post()
                    .uri("/v1/policies")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Seeded demo policy");
        } catch (Exception createFailed) {
            try {
                String id = body.contains("payment-tenant-limit") ? "payment-tenant-limit" : "payment-user-limit";
                restClient.put()
                        .uri("/v1/policies/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                log.info("Updated demo policy {}", id);
            } catch (Exception updateFailed) {
                log.warn("Could not seed demo policies: {}", updateFailed.getMessage());
            }
        }
    }
}
