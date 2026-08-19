package com.throttl.ratelimiter.api;

import com.throttl.ratelimiter.api.dto.PolicyRequest;
import com.throttl.ratelimiter.domain.Algorithm;
import com.throttl.ratelimiter.domain.LimitingStrategy;
import com.throttl.ratelimiter.domain.Matching;
import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.domain.PolicyMetadata;
import com.throttl.ratelimiter.domain.PolicyValidationException;
import com.throttl.ratelimiter.store.PolicyMapper;

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
        if (request.strategy().algorithm() != null && request.strategy().algorithm() != Algorithm.TOKEN_BUCKET) {
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

        try {
            return new Policy(
                    request.id(),
                    request.name(),
                    new Matching(request.matching().scope(), request.matching().conditions()),
                    new LimitingStrategy(
                            request.strategy().algorithm(),
                            PolicyMapper.toTokenBucketConfiguration(configuration)
                    ),
                    new PolicyMetadata(enabled, priority),
                    null,
                    null
            );
        } catch (IllegalArgumentException ex) {
            throw new PolicyValidationException(ex.getMessage());
        }
    }
}
