package com.throttl.ratelimiter.api.dto;

import com.throttl.ratelimiter.domain.Algorithm;

import java.util.Map;

public record StrategyRequest(Algorithm algorithm, Map<String, Object> configuration) {
}
