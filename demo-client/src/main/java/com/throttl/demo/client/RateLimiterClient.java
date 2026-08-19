package com.throttl.demo.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RateLimiterClient {

    private final RestClient restClient;

    public RateLimiterClient(@Value("${rate-limiter.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public RateLimitDecision check(RateLimitContext context) {
        return restClient.post()
                .uri("/v1/check")
                .contentType(MediaType.APPLICATION_JSON)
                .body(context)
                .retrieve()
                .body(RateLimitDecision.class);
    }
}
