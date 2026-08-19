package com.throttl.ratelimiter.store;

import com.throttl.ratelimiter.domain.Algorithm;
import com.throttl.ratelimiter.domain.LimitingStrategy;
import com.throttl.ratelimiter.domain.Matching;
import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.domain.PolicyMetadata;
import com.throttl.ratelimiter.domain.RefillPeriod;
import com.throttl.ratelimiter.domain.TokenBucketConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PolicyMapper {

    private PolicyMapper() {
    }

    public static Policy toDomain(PolicyEntity entity) {
        TokenBucketConfiguration configuration = toTokenBucketConfiguration(entity.getConfiguration());
        return new Policy(
                entity.getId(),
                entity.getName(),
                new Matching(entity.getScope(), entity.getConditions() == null ? Map.of() : entity.getConditions()),
                new LimitingStrategy(entity.getAlgorithm(), configuration),
                new PolicyMetadata(entity.isEnabled(), entity.getPriority()),
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
        entity.setConfiguration(fromTokenBucketConfiguration(policy.strategy().configuration()));
        entity.setEnabled(policy.metadata().enabled());
        entity.setPriority(policy.metadata().priority());
    }

    public static TokenBucketConfiguration toTokenBucketConfiguration(Map<String, Object> configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Missing configuration");
        }
        long capacity = asLong(configuration.get("capacity"), "capacity");
        long refillRate = asLong(configuration.get("refillRate"), "refillRate");
        Object periodValue = configuration.get("refillPeriod");
        if (periodValue == null) {
            throw new IllegalArgumentException("Invalid refill period");
        }
        RefillPeriod period;
        try {
            period = RefillPeriod.valueOf(String.valueOf(periodValue));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid refill period");
        }
        return new TokenBucketConfiguration(capacity, refillRate, period);
    }

    public static Map<String, Object> fromTokenBucketConfiguration(TokenBucketConfiguration configuration) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("capacity", configuration.capacity());
        map.put("refillRate", configuration.refillRate());
        map.put("refillPeriod", configuration.refillPeriod().name());
        map.put("algorithm", Algorithm.TOKEN_BUCKET.name());
        return map;
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
