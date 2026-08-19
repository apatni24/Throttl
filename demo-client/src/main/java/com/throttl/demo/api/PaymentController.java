package com.throttl.demo.api;

import com.throttl.demo.client.RateLimitContext;
import com.throttl.demo.client.RateLimitDecision;
import com.throttl.demo.client.RateLimiterClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping
public class PaymentController {

    private final RateLimiterClient rateLimiterClient;

    public PaymentController(RateLimiterClient rateLimiterClient) {
        this.rateLimiterClient = rateLimiterClient;
    }

    @PostMapping("/payments")
    public ResponseEntity<?> createPayment(
            @RequestHeader(value = "X-User-Id", defaultValue = "u123") String userId,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "acme") String tenantId,
            HttpServletRequest request
    ) {
        RateLimitDecision decision = rateLimiterClient.check(RateLimitContext.builder()
                .userId(userId)
                .tenantId(tenantId)
                .api("payment")
                .ip(request.getRemoteAddr())
                .build());
        if (!decision.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(Math.max(1, decision.retryAfterMs() / 1000)))
                    .body(Map.of(
                            "error", "rate_limited",
                            "rejectedBy", decision.rejectedBy(),
                            "remaining", decision.remaining(),
                            "retryAfterMs", decision.retryAfterMs()
                    ));
        }
        return ResponseEntity.ok(Map.of(
                "id", UUID.randomUUID().toString(),
                "status", "ACCEPTED",
                "remaining", decision.remaining(),
                "createdAt", Instant.now().toString()
        ));
    }

    @GetMapping("/accounts")
    public Map<String, Object> accounts(
            @RequestHeader(value = "X-User-Id", defaultValue = "u123") String userId,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "acme") String tenantId,
            HttpServletRequest request
    ) {
        rateLimiterClient.check(RateLimitContext.builder()
                .userId(userId)
                .tenantId(tenantId)
                .api("accounts")
                .ip(request.getRemoteAddr())
                .build());
        return Map.of("userId", userId, "balance", 1250.50);
    }
}
