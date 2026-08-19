package com.throttl.ratelimiter.api.dto;

import com.throttl.ratelimiter.domain.Policy;

import java.time.Instant;

public record PolicyResponse(
        String id,
        String name,
        MatchingRequest matching,
        StrategyRequest strategy,
        MetadataResponse metadata
) {

    public record MetadataResponse(boolean enabled, int priority, Instant createdAt, Instant updatedAt) {
    }

    public static PolicyResponse from(Policy policy) {
        return new PolicyResponse(
                policy.id(),
                policy.name(),
                new MatchingRequest(policy.matching().scope(), policy.matching().conditions()),
                new StrategyRequest(
                        policy.strategy().algorithm(),
                        java.util.Map.of(
                                "capacity", policy.strategy().configuration().capacity(),
                                "refillRate", policy.strategy().configuration().refillRate(),
                                "refillPeriod", policy.strategy().configuration().refillPeriod().name()
                        )
                ),
                new MetadataResponse(
                        policy.metadata().enabled(),
                        policy.metadata().priority(),
                        policy.createdAt(),
                        policy.updatedAt()
                )
        );
    }
}
