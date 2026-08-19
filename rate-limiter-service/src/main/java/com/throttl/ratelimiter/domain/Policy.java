package com.throttl.ratelimiter.domain;

import java.time.Instant;

public record Policy(
        String id,
        String name,
        Matching matching,
        LimitingStrategy strategy,
        PolicyMetadata metadata,
        Instant createdAt,
        Instant updatedAt
) {
}
