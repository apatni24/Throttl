package com.throttl.ratelimiter.resolver;

import com.throttl.ratelimiter.api.dto.RateLimitCheckRequest;
import com.throttl.ratelimiter.domain.Algorithm;
import com.throttl.ratelimiter.domain.LimitingStrategy;
import com.throttl.ratelimiter.domain.Matching;
import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.domain.PolicyMetadata;
import com.throttl.ratelimiter.domain.RefillPeriod;
import com.throttl.ratelimiter.domain.Scope;
import com.throttl.ratelimiter.domain.TokenBucketConfiguration;
import io.lettuce.core.cluster.SlotHash;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyResolverTest {

    @Test
    void matchesUserPolicyWhenApiConditionHolds() {
        Policy policy = policy("payment-user-limit", Scope.USER, Map.of("api", "payment"), 10, true);
        RateLimitCheckRequest request = new RateLimitCheckRequest("u123", "acme", "payment", "10.0.0.1");
        assertThat(PolicyResolver.matches(policy, request)).isTrue();
        assertThat(PolicyResolver.bucketKey(policy, request)).isEqualTo("rl:{USER:u123}:payment-user-limit");
    }

    @Test
    void doesNotMatchWhenApiDiffers() {
        Policy policy = policy("payment-user-limit", Scope.USER, Map.of("api", "payment"), 10, true);
        RateLimitCheckRequest request = new RateLimitCheckRequest("u123", "acme", "accounts", "10.0.0.1");
        assertThat(PolicyResolver.matches(policy, request)).isFalse();
    }

    @Test
    void doesNotMatchWhenScopeValueMissing() {
        Policy policy = policy("payment-user-limit", Scope.USER, Map.of(), 10, true);
        RateLimitCheckRequest request = new RateLimitCheckRequest(null, "acme", "payment", "10.0.0.1");
        assertThat(PolicyResolver.matches(policy, request)).isFalse();
    }

    @Test
    void returnsApplicablePoliciesSortedByPriority() {
        Policy user = policy("payment-user-limit", Scope.USER, Map.of("api", "payment"), 10, true);
        Policy tenant = policy("payment-tenant-limit", Scope.TENANT, Map.of("api", "payment"), 20, true);
        Policy api = policy("payment-api-limit", Scope.API, Map.of("api", "payment"), 5, true);
        Policy disabled = policy("disabled", Scope.USER, Map.of("api", "payment"), 99, false);
        Policy otherApi = policy("other", Scope.API, Map.of("api", "search"), 50, true);

        RateLimitCheckRequest request = new RateLimitCheckRequest("u123", "acme", "payment", "10.0.0.1");
        List<Policy> resolved = PolicyResolver.resolve(List.of(user, tenant, api, disabled, otherApi), request);

        assertThat(resolved).extracting(Policy::id)
                .containsExactly("payment-tenant-limit", "payment-user-limit", "payment-api-limit");
    }

    @Test
    void matchesIpAndTenantScopes() {
        Policy ip = policy("ip-limit", Scope.IP, Map.of(), 1, true);
        Policy tenant = policy("tenant-limit", Scope.TENANT, Map.of(), 1, true);
        RateLimitCheckRequest request = new RateLimitCheckRequest("u123", "acme", "payment", "10.0.0.1");
        assertThat(PolicyResolver.matches(ip, request)).isTrue();
        assertThat(PolicyResolver.matches(tenant, request)).isTrue();
        assertThat(PolicyResolver.bucketKey(ip, request)).isEqualTo("rl:{IP:10.0.0.1}:ip-limit");
        assertThat(PolicyResolver.bucketKey(tenant, request)).isEqualTo("rl:{TENANT:acme}:tenant-limit");
    }

    @Test
    void policiesForTheSameSubjectShareAHashSlot() {
        Policy perSecond = policy("user-per-second", Scope.USER, Map.of(), 10, true);
        Policy perDay = policy("user-per-day", Scope.USER, Map.of("api", "payment"), 5, true);
        RateLimitCheckRequest request = new RateLimitCheckRequest("u123", "acme", "payment", "10.0.0.1");

        assertThat(SlotHash.getSlot(PolicyResolver.bucketKey(perSecond, request)))
                .isEqualTo(SlotHash.getSlot(PolicyResolver.bucketKey(perDay, request)));
    }

    @Test
    void bucketKeyPatternMatchesOnlyThatPolicy() {
        assertThat(PolicyResolver.bucketKeyPattern("payment-user-limit")).isEqualTo("rl:{*}:payment-user-limit");
    }

    private static Policy policy(String id, Scope scope, Map<String, String> conditions, int priority, boolean enabled) {
        return new Policy(
                id,
                id,
                new Matching(scope, conditions),
                new LimitingStrategy(Algorithm.TOKEN_BUCKET, new TokenBucketConfiguration(100, 10, RefillPeriod.SECOND)),
                new PolicyMetadata(enabled, priority),
                null,
                null
        );
    }
}
