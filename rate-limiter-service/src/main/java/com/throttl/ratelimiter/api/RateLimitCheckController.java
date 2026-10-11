package com.throttl.ratelimiter.api;

import com.throttl.ratelimiter.api.dto.RateLimitCheckRequest;
import com.throttl.ratelimiter.api.dto.RateLimitCheckResponse;
import com.throttl.ratelimiter.config.RateLimiterProperties;
import com.throttl.ratelimiter.engine.RateLimitDecision;
import com.throttl.ratelimiter.engine.RateLimitEngine;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/v1")
public class RateLimitCheckController {

    private final RateLimitEngine engine;
    private final RateLimiterProperties properties;

    public RateLimitCheckController(RateLimitEngine engine, RateLimiterProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    @PostMapping("/check")
    public CompletableFuture<RateLimitCheckResponse> check(@RequestBody RateLimitCheckRequest request) {
        RateLimitCheckRequest body = request == null
                ? new RateLimitCheckRequest(null, null, null, null)
                : request;
        if (!properties.asyncCheck()) {
            return CompletableFuture.completedFuture(toResponse(engine.check(body)));
        }
        return engine.checkReactive(body).map(this::toResponse).toFuture();
    }

    private RateLimitCheckResponse toResponse(RateLimitDecision decision) {
        return new RateLimitCheckResponse(
                decision.allowed(),
                decision.remaining(),
                decision.retryAfterMs(),
                decision.rejectedBy(),
                decision.evaluations()
        );
    }
}
