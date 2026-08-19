package com.throttl.ratelimiter.api;

import com.throttl.ratelimiter.api.dto.RateLimitCheckRequest;
import com.throttl.ratelimiter.api.dto.RateLimitCheckResponse;
import com.throttl.ratelimiter.engine.RateLimitDecision;
import com.throttl.ratelimiter.engine.RateLimitEngine;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class RateLimitCheckController {

    private final RateLimitEngine engine;

    public RateLimitCheckController(RateLimitEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/check")
    public RateLimitCheckResponse check(@RequestBody RateLimitCheckRequest request) {
        RateLimitDecision decision = engine.check(request == null
                ? new RateLimitCheckRequest(null, null, null, null)
                : request);
        return new RateLimitCheckResponse(
                decision.allowed(),
                decision.remaining(),
                decision.retryAfterMs(),
                decision.rejectedBy()
        );
    }
}
