package com.throttl.ratelimiter.resolver;

import com.throttl.ratelimiter.api.dto.RateLimitCheckRequest;
import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.domain.Scope;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class PolicyResolver {

    private PolicyResolver() {
    }

    public static List<Policy> resolve(List<Policy> policies, RateLimitCheckRequest request) {
        return policies.stream()
                .filter(policy -> policy.metadata().enabled())
                .filter(policy -> matches(policy, request))
                .sorted(Comparator.comparingInt((Policy policy) -> policy.metadata().priority()).reversed()
                        .thenComparing(Policy::id))
                .toList();
    }

    public static boolean matches(Policy policy, RateLimitCheckRequest request) {
        if (!hasScopeValue(policy.matching().scope(), request)) {
            return false;
        }
        Map<String, String> conditions = policy.matching().conditions();
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> condition : conditions.entrySet()) {
            String actual = attribute(request, condition.getKey());
            if (actual == null || !actual.equals(condition.getValue())) {
                return false;
            }
        }
        return true;
    }

    public static String scopeValue(Policy policy, RateLimitCheckRequest request) {
        return switch (policy.matching().scope()) {
            case USER -> request.userId();
            case TENANT -> request.tenantId();
            case API -> request.api();
            case IP -> request.ip();
        };
    }

    /**
     * {@code rl:{SCOPE:value}:policyId}. The braces are a Redis Cluster hash tag:
     * every policy limiting the same subject lands on one slot and can be
     * consumed in one atomic script, while different subjects still spread out.
     */
    public static String bucketKey(Policy policy, RateLimitCheckRequest request) {
        return "rl:{" + policy.matching().scope() + ":" + scopeValue(policy, request) + "}:" + policy.id();
    }

    /** SCAN pattern for every bucket of one policy. Policy ids cannot contain glob characters. */
    public static String bucketKeyPattern(String policyId) {
        return "rl:{*}:" + policyId;
    }

    private static boolean hasScopeValue(Scope scope, RateLimitCheckRequest request) {
        String value = switch (scope) {
            case USER -> request.userId();
            case TENANT -> request.tenantId();
            case API -> request.api();
            case IP -> request.ip();
        };
        return value != null && !value.isBlank();
    }

    private static String attribute(RateLimitCheckRequest request, String key) {
        return switch (key) {
            case "userId", "user" -> request.userId();
            case "tenantId", "tenant" -> request.tenantId();
            case "api" -> request.api();
            case "ip" -> request.ip();
            default -> null;
        };
    }
}
