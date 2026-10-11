package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.domain.RefillPeriod;
import com.throttl.ratelimiter.domain.TokenBucketConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketMathTest {

    private static final TokenBucketConfiguration CONFIG =
            new TokenBucketConfiguration(100, 10, RefillPeriod.SECOND);

    @Test
    void bucketStartsFullAndConsumesOneToken() {
        TokenBucketMath.ConsumeResult first = TokenBucketMath.consume(null, CONFIG, 1_000L, 1);
        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(99);
    }

    @Test
    void burstAllowsCapacityThenDenies() {
        TokenBucketMath.BucketState state = null;
        long allowed = 0;
        long now = 0;
        for (int i = 0; i < 110; i++) {
            TokenBucketMath.ConsumeResult result = TokenBucketMath.consume(state, CONFIG, now, 1);
            state = result.state();
            if (result.allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(100);
        TokenBucketMath.ConsumeResult denied = TokenBucketMath.consume(state, CONFIG, now, 1);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterMs()).isEqualTo(1000);
    }

    @Test
    void refillsAfterOneSecond() {
        TokenBucketMath.ConsumeResult exhausted = drain(CONFIG, 0);
        assertThat(exhausted.remaining()).isZero();

        TokenBucketMath.ConsumeResult refilled = TokenBucketMath.consume(exhausted.state(), CONFIG, 1_000L, 1);
        assertThat(refilled.allowed()).isTrue();
        assertThat(refilled.remaining()).isEqualTo(9);
    }

    @Test
    void refillIsCappedAtCapacity() {
        TokenBucketMath.ConsumeResult first = TokenBucketMath.consume(null, CONFIG, 0, 1);
        TokenBucketMath.ConsumeResult later = TokenBucketMath.consume(first.state(), CONFIG, 60_000L, 1);
        assertThat(later.allowed()).isTrue();
        assertThat(later.remaining()).isEqualTo(99);
    }

    @Test
    void multiplePoliciesRequireAllToPass() {
        TokenBucketConfiguration tight = new TokenBucketConfiguration(1, 1, RefillPeriod.MINUTE);
        List<TokenBucketMath.ConsumeResult> first = TokenBucketMath.consumeAllOrNone(
                Arrays.asList(null, null),
                List.of(CONFIG, tight),
                0,
                1
        );
        assertThat(first.get(0).allowed()).isTrue();
        assertThat(first.get(1).allowed()).isTrue();
        assertThat(first.get(0).remaining()).isEqualTo(99);
        assertThat(first.get(1).remaining()).isZero();

        List<TokenBucketMath.ConsumeResult> second = TokenBucketMath.consumeAllOrNone(
                List.of(first.get(0).state(), first.get(1).state()),
                List.of(CONFIG, tight),
                0,
                1
        );
        assertThat(second.get(0).allowed()).isTrue();
        assertThat(second.get(0).remaining()).isEqualTo(99);
        assertThat(second.get(1).allowed()).isFalse();
        assertThat(second.stream().anyMatch(result -> !result.allowed())).isTrue();
    }

    private static TokenBucketMath.ConsumeResult drain(TokenBucketConfiguration configuration, long now) {
        TokenBucketMath.BucketState state = null;
        TokenBucketMath.ConsumeResult last = null;
        for (int i = 0; i < configuration.capacity(); i++) {
            last = TokenBucketMath.consume(state, configuration, now, 1);
            state = last.state();
        }
        return last;
    }
}
