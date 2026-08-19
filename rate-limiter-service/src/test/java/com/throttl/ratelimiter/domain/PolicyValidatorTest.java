package com.throttl.ratelimiter.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyValidatorTest {

    @Test
    void acceptsValidPolicy() {
        PolicyValidator.validate(validPolicy());
    }

    @Test
    void rejectsMissingId() {
        Policy policy = new Policy(null, "n", matching(), strategy(), metadata(), null, null);
        assertThatThrownBy(() -> PolicyValidator.validate(policy))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("id");
    }

    @Test
    void rejectsInvalidId() {
        Policy policy = new Policy("bad id!", "n", matching(), strategy(), metadata(), null, null);
        assertThatThrownBy(() -> PolicyValidator.validate(policy))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("id");
    }

    @Test
    void rejectsMissingName() {
        Policy policy = new Policy("payment-user-limit", " ", matching(), strategy(), metadata(), null, null);
        assertThatThrownBy(() -> PolicyValidator.validate(policy))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsMissingMatching() {
        Policy policy = new Policy("payment-user-limit", "n", null, strategy(), metadata(), null, null);
        assertThatThrownBy(() -> PolicyValidator.validate(policy))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("Matching");
    }

    @Test
    void rejectsMissingScope() {
        Policy policy = new Policy(
                "payment-user-limit",
                "n",
                new Matching(null, Map.of()),
                strategy(),
                metadata(),
                null,
                null
        );
        assertThatThrownBy(() -> PolicyValidator.validate(policy))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void rejectsMissingConfiguration() {
        Policy policy = new Policy(
                "payment-user-limit",
                "n",
                matching(),
                new LimitingStrategy(Algorithm.TOKEN_BUCKET, null),
                metadata(),
                null,
                null
        );
        assertThatThrownBy(() -> PolicyValidator.validate(policy))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("configuration");
    }

    @Test
    void rejectsNonPositiveCapacity() {
        Policy policy = new Policy(
                "payment-user-limit",
                "n",
                matching(),
                new LimitingStrategy(Algorithm.TOKEN_BUCKET, new TokenBucketConfiguration(0, 10, RefillPeriod.SECOND)),
                metadata(),
                null,
                null
        );
        assertThatThrownBy(() -> PolicyValidator.validate(policy))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("Capacity");
    }

    @Test
    void rejectsNonPositiveRefillRate() {
        Policy policy = new Policy(
                "payment-user-limit",
                "n",
                matching(),
                new LimitingStrategy(Algorithm.TOKEN_BUCKET, new TokenBucketConfiguration(100, 0, RefillPeriod.SECOND)),
                metadata(),
                null,
                null
        );
        assertThatThrownBy(() -> PolicyValidator.validate(policy))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("Refill rate");
    }

    @Test
    void rejectsInvalidRefillPeriod() {
        Policy policy = new Policy(
                "payment-user-limit",
                "n",
                matching(),
                new LimitingStrategy(Algorithm.TOKEN_BUCKET, new TokenBucketConfiguration(100, 10, null)),
                metadata(),
                null,
                null
        );
        assertThatThrownBy(() -> PolicyValidator.validate(policy))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("refill period");
    }

    @Test
    void rejectsNegativePriority() {
        Policy policy = new Policy(
                "payment-user-limit",
                "n",
                matching(),
                strategy(),
                new PolicyMetadata(true, -1),
                null,
                null
        );
        assertThatThrownBy(() -> PolicyValidator.validate(policy))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("Priority");
    }

    @Test
    void allMvpScopesAreValid() {
        for (Scope scope : Scope.values()) {
            Policy policy = new Policy(
                    "policy-" + scope.name().toLowerCase(),
                    "n",
                    new Matching(scope, Map.of()),
                    strategy(),
                    metadata(),
                    null,
                    null
            );
            PolicyValidator.validate(policy);
            assertThat(policy.matching().scope()).isEqualTo(scope);
        }
    }

    private static Policy validPolicy() {
        return new Policy("payment-user-limit", "Payment User Limit", matching(), strategy(), metadata(), null, null);
    }

    private static Matching matching() {
        return new Matching(Scope.USER, Map.of("api", "payment"));
    }

    private static LimitingStrategy strategy() {
        return new LimitingStrategy(Algorithm.TOKEN_BUCKET, new TokenBucketConfiguration(100, 10, RefillPeriod.SECOND));
    }

    private static PolicyMetadata metadata() {
        return new PolicyMetadata(true, 10);
    }
}
