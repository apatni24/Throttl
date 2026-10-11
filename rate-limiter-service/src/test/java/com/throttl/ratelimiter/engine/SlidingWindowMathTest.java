package com.throttl.ratelimiter.engine;

import com.throttl.ratelimiter.domain.RefillPeriod;
import com.throttl.ratelimiter.domain.SlidingWindowConfiguration;
import com.throttl.ratelimiter.engine.SlidingWindowMath.ConsumeResult;
import com.throttl.ratelimiter.engine.SlidingWindowMath.WindowState;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowMathTest {

    private static final SlidingWindowConfiguration TEN_PER_SECOND =
            new SlidingWindowConfiguration(10, RefillPeriod.SECOND);

    @Test
    void allowsUpToLimitInsideOneWindow() {
        WindowState state = null;
        long now = 5_000;
        for (int i = 0; i < 10; i++) {
            ConsumeResult result = SlidingWindowMath.consume(state, TEN_PER_SECOND, now, 1);
            assertThat(result.allowed()).isTrue();
            assertThat(result.remaining()).isEqualTo(9 - i);
            state = result.state();
        }
        ConsumeResult denied = SlidingWindowMath.consume(state, TEN_PER_SECOND, now, 1);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMs()).isPositive();
    }

    @Test
    void smoothsTheBoundaryBurstAFixedWindowWouldAllow() {
        WindowState state = null;
        for (int i = 0; i < 10; i++) {
            state = SlidingWindowMath.consume(state, TEN_PER_SECOND, 5_900, 1).state();
        }

        int allowedAfterBoundary = 0;
        for (int i = 0; i < 10; i++) {
            ConsumeResult result = SlidingWindowMath.consume(state, TEN_PER_SECOND, 6_100, 1);
            state = result.state();
            if (result.allowed()) {
                allowedAfterBoundary++;
            }
        }
        // 100ms into the next window, 90% of the previous window still counts: 10 * 0.9 = 9.
        assertThat(allowedAfterBoundary).isEqualTo(1);
    }

    @Test
    void skippedWindowForgetsOldTraffic() {
        WindowState state = null;
        for (int i = 0; i < 10; i++) {
            state = SlidingWindowMath.consume(state, TEN_PER_SECOND, 5_500, 1).state();
        }
        ConsumeResult later = SlidingWindowMath.consume(state, TEN_PER_SECOND, 7_100, 1);
        assertThat(later.allowed()).isTrue();
        assertThat(later.remaining()).isEqualTo(9);
    }

    @Test
    void windowStartInTheFutureIsNotRolledBack() {
        WindowState ahead = new WindowState(6_000, 10, 0);
        ConsumeResult result = SlidingWindowMath.consume(ahead, TEN_PER_SECOND, 5_990, 1);
        assertThat(result.allowed()).isFalse();
        assertThat(result.state().windowStart()).isEqualTo(6_000);
    }

    @Test
    void retryAfterIsTheEarliestMomentARequestIsAllowed() {
        Random random = new Random(42);
        SlidingWindowConfiguration config = new SlidingWindowConfiguration(20, RefillPeriod.SECOND);
        for (int trial = 0; trial < 500; trial++) {
            WindowState state = null;
            long now = 10_000 + random.nextInt(3_000);
            ConsumeResult result = SlidingWindowMath.consume(null, config, now, 1);
            while (result.allowed()) {
                state = result.state();
                now += random.nextInt(40);
                result = SlidingWindowMath.consume(state, config, now, 1);
            }

            long retryAfter = result.retryAfterMs();
            assertThat(retryAfter).isPositive();
            assertThat(SlidingWindowMath.consume(state, config, now + retryAfter, 1).allowed())
                    .as("allowed once retryAfter elapses (trial %d)", trial)
                    .isTrue();
            assertThat(SlidingWindowMath.consume(state, config, now + retryAfter - 1, 1).allowed())
                    .as("still denied just before retryAfter (trial %d)", trial)
                    .isFalse();
        }
    }
}
