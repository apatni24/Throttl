package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.api.dto.RateLimitCheckRequest;
import com.throttl.ratelimiter.config.RateLimiterProperties;
import com.throttl.ratelimiter.domain.Algorithm;
import com.throttl.ratelimiter.domain.LimitingStrategy;
import com.throttl.ratelimiter.domain.Matching;
import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.domain.PolicyMetadata;
import com.throttl.ratelimiter.domain.RefillPeriod;
import com.throttl.ratelimiter.domain.Scope;
import com.throttl.ratelimiter.domain.TokenBucketConfiguration;
import com.throttl.ratelimiter.metrics.RateLimitMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitEngineTest {

    @Test
    void failClosedWhenRedisThrows() {
        PolicyCatalog catalog = mock(PolicyCatalog.class);
        TokenBucketAlgorithm algorithm = mock(TokenBucketAlgorithm.class);
        when(catalog.enabledPolicies()).thenReturn(List.of(policy()));
        when(algorithm.evaluateAll(any(), any())).thenThrow(new RuntimeException("Redis down"));

        RateLimitEngine engine = new RateLimitEngine(
                catalog,
                algorithm,
                new RateLimitMetrics(new SimpleMeterRegistry()),
                new RateLimiterProperties()
        );

        RateLimitDecision decision = engine.check(new RateLimitCheckRequest("u1", "t1", "payment", "1.1.1.1"));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.rejectedBy()).isEqualTo("redis-unavailable");
    }

    @Test
    void failClosedWhenReactiveRedisThrows() {
        PolicyCatalog catalog = mock(PolicyCatalog.class);
        TokenBucketAlgorithm algorithm = mock(TokenBucketAlgorithm.class);
        when(catalog.enabledPoliciesAsync()).thenReturn(reactor.core.publisher.Mono.just(List.of(policy())));
        when(algorithm.evaluateAllReactive(any(), any()))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("Redis down")));

        RateLimitEngine engine = new RateLimitEngine(
                catalog,
                algorithm,
                new RateLimitMetrics(new SimpleMeterRegistry()),
                new RateLimiterProperties()
        );

        RateLimitDecision decision = engine.checkReactive(new RateLimitCheckRequest("u1", "t1", "payment", "1.1.1.1"))
                .block();
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.rejectedBy()).isEqualTo("redis-unavailable");
    }

    @Test
    void allowsWhenNoPoliciesMatch() {
        PolicyCatalog catalog = mock(PolicyCatalog.class);
        when(catalog.enabledPolicies()).thenReturn(List.of());
        RateLimitEngine engine = new RateLimitEngine(
                catalog,
                mock(TokenBucketAlgorithm.class),
                new RateLimitMetrics(new SimpleMeterRegistry()),
                new RateLimiterProperties()
        );
        assertThat(engine.check(new RateLimitCheckRequest("u1", "t1", "payment", "1.1.1.1")).allowed()).isTrue();
    }

    private static Policy policy() {
        return new Policy(
                "payment-user-limit",
                "n",
                new Matching(Scope.USER, Map.of("api", "payment")),
                new LimitingStrategy(Algorithm.TOKEN_BUCKET, new TokenBucketConfiguration(100, 10, RefillPeriod.SECOND)),
                new PolicyMetadata(true, 10),
                null,
                null
        );
    }
}
