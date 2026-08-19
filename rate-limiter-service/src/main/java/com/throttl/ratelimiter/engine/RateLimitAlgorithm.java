package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.domain.Policy;

public interface RateLimitAlgorithm {

    RateLimitDecision evaluate(Policy policy, String bucketKey);
}
