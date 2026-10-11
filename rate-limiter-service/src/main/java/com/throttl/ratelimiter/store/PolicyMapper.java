package com.throttl.ratelimiter.store;

import com.throttl.ratelimiter.domain.Algorithm;
import com.throttl.ratelimiter.domain.LimitConfiguration;
import com.throttl.ratelimiter.domain.LimitingStrategy;
import com.throttl.ratelimiter.domain.Matching;
import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.domain.PolicyMetadata;
import com.throttl.ratelimiter.domain.RefillPeriod;
import com.throttl.ratelimiter.domain.SlidingWindowConfiguration;
import com.throttl.ratelimiter.domain.TokenBucketConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PolicyMapper {

    private PolicyMapper() {
    }

    public static Policy toDomain(PolicyEntity entity) {
        LimitConfiguration configuration = toConfiguration(entity.getAlgorithm(), entity.getConfiguration());
        return new Policy(
                entity.getId(),
                entity.getName(),
                new Matching(entity.getScope(), entity.getConditions() == null ? Map.of() : entity.getConditions()),
                new LimitingStrategy(entity.getAlgorithm(), configuration),
                new PolicyMetadata(entity.isEnabled(), entity.getPriority(), entity.getFailureMode()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public static PolicyEntity toNewEntity(Policy policy) {
        PolicyEntity entity = new PolicyEntity();
        apply(entity, policy);
        return entity;
    }

    public static void apply(PolicyEntity entity, Policy policy) {
        entity.setId(policy.id());
        entity.setName(policy.name());
        entity.setScope(policy.matching().scope());
        entity.setConditions(policy.matching().conditions() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(policy.matching().conditions()));
        entity.setAlgorithm(policy.strategy().algorithm());
        entity.setConfiguration(fromConfiguration(policy.strategy().configuration()));
        entity.setEnabled(policy.metadata().enabled());
        entity.setPriority(policy.metadata().priority());
        entity.setFailureMode(policy.metadata().failureMode());
    }

    public static LimitConfiguration toConfiguration(Algorithm algorithm, Map<String, Object> configuration) {
        if (algorithm == null) {
            throw new IllegalArgumentException("Invalid algorithm");
        }
        if (configuration == null) {
            throw new IllegalArgumentException("Missing configuration");
        }
        return switch (algorithm) {
            case TOKEN_BUCKET -> new TokenBucketConfiguration(
                    asLong(configuration.get("capacity"), "capacity"),
                    asLong(configuration.get("refillRate"), "refillRate"),
                    asPeriod(configuration.get("refillPeriod"), "refill period")
            );
            case SLIDING_WINDOW -> new SlidingWindowConfiguration(
                    asLong(configuration.get("limit"), "limit"),
                    asPeriod(configuration.get("window"), "window")
            );
        };
    }

    public static Map<String, Object> fromConfiguration(LimitConfiguration configuration) {
        Map<String, Object> map = new LinkedHashMap<>();
        switch (configuration) {
            case TokenBucketConfiguration tokenBucket -> {
                map.put("capacity", tokenBucket.capacity());
                map.put("refillRate", tokenBucket.refillRate());
                map.put("refillPeriod", tokenBucket.refillPeriod().name());
            }
            case SlidingWindowConfiguration slidingWindow -> {
                map.put("limit", slidingWindow.limit());
                map.put("window", slidingWindow.window().name());
            }
        }
        return map;
    }

    private static RefillPeriod asPeriod(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        try {
            return RefillPeriod.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    private static long asLong(Object value, String field) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                throw new IllegalArgumentException("Invalid " + field);
            }
        }
        throw new IllegalArgumentException("Missing configuration field: " + field);
    }
}
