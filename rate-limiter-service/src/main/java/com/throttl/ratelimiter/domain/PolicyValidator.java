package com.throttl.ratelimiter.domain;

import java.util.Map;
import java.util.regex.Pattern;

public final class PolicyValidator {

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}$");

    private PolicyValidator() {
    }

    public static void validate(Policy policy) {
        if (policy == null) {
            throw new PolicyValidationException("Policy is required");
        }
        validateId(policy.id());
        if (policy.name() == null || policy.name().isBlank()) {
            throw new PolicyValidationException("Policy name is required");
        }
        if (policy.name().length() > 255) {
            throw new PolicyValidationException("Policy name must be at most 255 characters");
        }
        validateMatching(policy.matching());
        validateStrategy(policy.strategy());
        validateMetadata(policy.metadata());
    }

    public static void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new PolicyValidationException("Policy id is required");
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new PolicyValidationException("Policy id must be 1-128 characters of letters, digits, '.', '_', ':', or '-'");
        }
    }

    private static void validateMatching(Matching matching) {
        if (matching == null) {
            throw new PolicyValidationException("Matching is required");
        }
        if (matching.scope() == null) {
            throw new PolicyValidationException("Invalid scope");
        }
        Map<String, String> conditions = matching.conditions() == null ? Map.of() : matching.conditions();
        for (Map.Entry<String, String> entry : conditions.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new PolicyValidationException("Condition keys must be non-blank");
            }
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                throw new PolicyValidationException("Condition values must be non-blank");
            }
        }
    }

    private static void validateStrategy(LimitingStrategy strategy) {
        if (strategy == null) {
            throw new PolicyValidationException("Strategy is required");
        }
        if (strategy.algorithm() == null) {
            throw new PolicyValidationException("Invalid algorithm");
        }
        if (strategy.algorithm() != Algorithm.TOKEN_BUCKET) {
            throw new PolicyValidationException("Unsupported algorithm: " + strategy.algorithm());
        }
        TokenBucketConfiguration configuration = strategy.configuration();
        if (configuration == null) {
            throw new PolicyValidationException("Missing configuration");
        }
        if (configuration.capacity() <= 0) {
            throw new PolicyValidationException("Capacity must be greater than 0");
        }
        if (configuration.refillRate() <= 0) {
            throw new PolicyValidationException("Refill rate must be greater than 0");
        }
        if (configuration.refillPeriod() == null) {
            throw new PolicyValidationException("Invalid refill period");
        }
    }

    private static void validateMetadata(PolicyMetadata metadata) {
        if (metadata == null) {
            throw new PolicyValidationException("Metadata is required");
        }
        if (metadata.priority() < 0) {
            throw new PolicyValidationException("Priority must be greater than or equal to 0");
        }
    }
}
