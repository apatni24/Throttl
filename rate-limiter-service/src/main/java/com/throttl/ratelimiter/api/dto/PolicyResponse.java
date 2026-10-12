package com.throttl.ratelimiter.api.dto;

import com.throttl.ratelimiter.domain.FailureMode;
import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.store.PolicyMapper;

import java.time.Instant;

public record PolicyResponse(
        String id,
        String name,
        MatchingRequest matching,
        StrategyRequest strategy,
        MetadataResponse metadata
) {

    public record MetadataResponse(
            boolean enabled,
            int priority,
            FailureMode failureMode,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public static PolicyResponse from(Policy policy) {
        return new PolicyResponse(
                policy.id(),
                policy.name(),
                new MatchingRequest(policy.matching().scope(), policy.matching().conditions()),
                new StrategyRequest(
                        policy.strategy().algorithm(),
                        PolicyMapper.fromConfiguration(policy.strategy().configuration()),
                        policy.strategy().lease()
                ),
                new MetadataResponse(
                        policy.metadata().enabled(),
                        policy.metadata().priority(),
                        policy.metadata().failureMode(),
                        policy.createdAt(),
                        policy.updatedAt()
                )
        );
    }
}
