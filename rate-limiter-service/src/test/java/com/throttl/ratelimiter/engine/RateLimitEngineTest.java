package com.throttl.ratelimiter.engine;

import com.github.benmanes.caffeine.cache.Ticker;
import com.throttl.ratelimiter.api.dto.RateLimitCheckRequest;
import com.throttl.ratelimiter.config.RateLimiterProperties;
import com.throttl.ratelimiter.domain.Algorithm;
import com.throttl.ratelimiter.domain.FailureMode;
import com.throttl.ratelimiter.domain.LimitingStrategy;
import com.throttl.ratelimiter.domain.Matching;
import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.domain.PolicyMetadata;
import com.throttl.ratelimiter.domain.RefillPeriod;
import com.throttl.ratelimiter.domain.Scope;
import com.throttl.ratelimiter.domain.SlidingWindowConfiguration;
import com.throttl.ratelimiter.domain.TokenBucketConfiguration;
import com.throttl.ratelimiter.metrics.RateLimitMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitEngineTest {

    private static final RateLimitCheckRequest REQUEST = new RateLimitCheckRequest("u1", "t1", "payment", "1.1.1.1");

    private final PolicyCatalog catalog = mock(PolicyCatalog.class);
    private final RedisRateLimiter redis = mock(RedisRateLimiter.class);
    private final RateLimiterProperties properties = new RateLimiterProperties();

    private RateLimitEngine engine() {
        RateLimitMetrics metrics = new RateLimitMetrics(new SimpleMeterRegistry());
        return new RateLimitEngine(
                catalog,
                redis,
                new LocalFallbackLimiter(3, () -> 1_000_000L),
                new LocalDenyCache(true, 1_000, Ticker.systemTicker()),
                new LeaseStore(Ticker.systemTicker(), metrics),
                metrics,
                properties
        );
    }

    private void redisDown(Policy... policies) {
        when(catalog.enabledPolicies()).thenReturn(List.of(policies));
        when(catalog.enabledPoliciesAsync()).thenReturn(Mono.just(List.of(policies)));
        when(redis.evaluateAll(any(), any(), any())).thenThrow(new RuntimeException("Redis down"));
        when(redis.evaluateAllReactive(any(), any(), any())).thenReturn(Mono.error(new RuntimeException("Redis down")));
    }

    @Test
    void policyWithoutModeInheritsServiceDefaultFailClosed() {
        redisDown(policy("payment-user-limit", Scope.USER, null));

        RateLimitDecision decision = engine().check(REQUEST);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.rejectedBy()).isEqualTo("redis-unavailable");
        assertThat(decision.evaluations()).extracting(PolicyEvaluation::status)
                .containsExactly(PolicyEvaluation.FAILED_CLOSED);
    }

    @Test
    void reactivePathAppliesTheSameDefault() {
        redisDown(policy("payment-user-limit", Scope.USER, null));

        RateLimitDecision decision = engine().checkReactive(REQUEST).block();

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.rejectedBy()).isEqualTo("redis-unavailable");
    }

    @Test
    void serviceDefaultCanBeFailOpen() {
        properties.setFailClosed(false);
        redisDown(policy("payment-user-limit", Scope.USER, null));

        RateLimitDecision decision = engine().check(REQUEST);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.degraded()).isTrue();
    }

    @Test
    void failOpenPolicyAllowsWhileRedisIsDown() {
        redisDown(policy("search-user-limit", Scope.USER, FailureMode.FAIL_OPEN));

        RateLimitDecision decision = engine().check(REQUEST);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.evaluations()).extracting(PolicyEvaluation::status)
                .containsExactly(PolicyEvaluation.FAILED_OPEN);
    }

    @Test
    void anyFailClosedPolicyDeniesEvenIfOthersFailOpen() {
        redisDown(
                policy("search-user-limit", Scope.USER, FailureMode.FAIL_OPEN),
                policy("payment-tenant-limit", Scope.TENANT, FailureMode.FAIL_CLOSED)
        );

        RateLimitDecision decision = engine().check(REQUEST);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.evaluations())
                .filteredOn(evaluation -> evaluation.policyId().equals("payment-tenant-limit"))
                .extracting(PolicyEvaluation::status)
                .containsExactly(PolicyEvaluation.FAILED_CLOSED);
    }

    @Test
    void localFallbackEnforcesThisInstancesShare() {
        // capacity 30 across 3 instances -> 10 per instance while Redis is down
        redisDown(policy("feed-user-limit", Scope.USER, FailureMode.LOCAL_FALLBACK));
        RateLimitEngine engine = engine();

        int allowed = 0;
        RateLimitDecision last = null;
        for (int i = 0; i < 15; i++) {
            last = engine.check(REQUEST);
            if (last.allowed()) {
                allowed++;
            }
        }

        assertThat(allowed).isEqualTo(10);
        assertThat(last.degraded()).isTrue();
        assertThat(last.rejectedBy()).isEqualTo("feed-user-limit");
        assertThat(last.retryAfterMs()).isPositive();
        assertThat(last.evaluations()).extracting(PolicyEvaluation::status)
                .containsExactly(PolicyEvaluation.LOCAL_REJECTED);
    }

    @Test
    void localFallbackIsAllOrNothingAcrossPolicies() {
        Policy roomy = policy("feed-user-limit", Scope.USER, FailureMode.LOCAL_FALLBACK);
        Policy tight = new Policy(
                "feed-tenant-window", "n", new Matching(Scope.TENANT, Map.of()),
                new LimitingStrategy(Algorithm.SLIDING_WINDOW, new SlidingWindowConfiguration(6, RefillPeriod.HOUR)),
                new PolicyMetadata(true, 5, FailureMode.LOCAL_FALLBACK), null, null);
        redisDown(roomy, tight);
        RateLimitEngine engine = engine();

        long allowed = java.util.stream.IntStream.range(0, 5).filter(i -> engine.check(REQUEST).allowed()).count();
        assertThat(allowed).as("6 / 3 instances = 2 per instance").isEqualTo(2);

        // The user bucket must only have paid for the 2 admitted requests: 10 - 2 = 8 left.
        when(catalog.enabledPolicies()).thenReturn(List.of(roomy));
        RateLimitDecision next = engine.check(REQUEST);
        assertThat(next.allowed()).isTrue();
        assertThat(next.remaining()).isEqualTo(7);
    }

    @Test
    void catalogFailureUsesServiceDefault() {
        when(catalog.enabledPolicies()).thenThrow(new RuntimeException("Postgres down"));
        RateLimitDecision closed = engine().check(REQUEST);
        assertThat(closed.allowed()).isFalse();
        assertThat(closed.rejectedBy()).isEqualTo("policy-catalog-unavailable");

        properties.setFailClosed(false);
        assertThat(engine().check(REQUEST).allowed()).isTrue();
    }

    @Test
    void allowsWhenNoPoliciesMatch() {
        when(catalog.enabledPolicies()).thenReturn(List.of());
        assertThat(engine().check(REQUEST).allowed()).isTrue();
    }

    private static Policy policy(String id, Scope scope, FailureMode failureMode) {
        return new Policy(
                id,
                "n",
                new Matching(scope, Map.of("api", "payment")),
                new LimitingStrategy(Algorithm.TOKEN_BUCKET, new TokenBucketConfiguration(30, 3, RefillPeriod.HOUR)),
                new PolicyMetadata(true, 10, failureMode),
                null,
                null
        );
    }
}
