package com.throttl.ratelimiter.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.throttl.ratelimiter.domain.Algorithm;
import com.throttl.ratelimiter.domain.LeaseConfiguration;

import java.util.Map;

public record StrategyRequest(
        Algorithm algorithm,
        Map<String, Object> configuration,
        @JsonInclude(JsonInclude.Include.NON_NULL) LeaseConfiguration lease
) {
}
