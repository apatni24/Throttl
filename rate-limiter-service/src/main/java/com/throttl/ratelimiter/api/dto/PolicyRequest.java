package com.throttl.ratelimiter.api.dto;

public record PolicyRequest(
        String id,
        String name,
        MatchingRequest matching,
        StrategyRequest strategy,
        MetadataRequest metadata
) {
}
