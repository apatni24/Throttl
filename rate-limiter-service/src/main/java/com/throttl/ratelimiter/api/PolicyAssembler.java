package com.throttl.ratelimiter.api;

import com.throttl.ratelimiter.api.dto.PolicyRequest;
import com.throttl.ratelimiter.domain.Algorithm;
import com.throttl.ratelimiter.domain.FailureMode;
import com.throttl.ratelimiter.domain.LimitingStrategy;
import com.throttl.ratelimiter.domain.Matching;
import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.domain.PolicyMetadata;
import com.throttl.ratelimiter.domain.PolicyValidationException;
import com.throttl.ratelimiter.store.PolicyMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PolicyAssembler {

    private PolicyAssembler() {
    }

    public static Policy fromRequest(PolicyRequest request) {
        if (request == null) {
            throw new PolicyValidationException("Policy is required");
        }
        if (request.matching() == null) {
            throw new PolicyValidationException("Matching is required");
        }
        if (request.strategy() == null) {
            throw new PolicyValidationException("Strategy is required");
        }
        Algorithm algorithm = request.strategy().algorithm();
        if (algorithm == null) {
            throw new PolicyValidationException("Invalid algorithm");
        }
        Map<String, Object> configuration = request.strategy().configuration();
        if (configuration == null) {
            throw new PolicyValidationException("Missing configuration");
        }

        boolean enabled = request.metadata() == null || request.metadata().enabled() == null
                || request.metadata().enabled();
        int priority = request.metadata() == null || request.metadata().priority() == null
                ? 0
                : request.metadata().priority();
        FailureMode failureMode = request.metadata() == null ? null : request.metadata().failureMode();

        try {
            return new Policy(
                    request.id(),
                    request.name(),
                    new Matching(request.matching().scope(), cleanedConditions(request.matching().conditions())),
                    new LimitingStrategy(algorithm, PolicyMapper.toConfiguration(algorithm, configuration)),
                    new PolicyMetadata(enabled, priority, failureMode),
                    null,
                    null
            );
        } catch (IllegalArgumentException ex) {
            throw new PolicyValidationException(ex.getMessage());
        }
    }

    static Map<String, String> cleanedConditions(Map<String, String> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return Map.of();
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : conditions.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            cleaned.put(entry.getKey().trim(), entry.getValue().trim());
        }
        return cleaned;
    }
}
