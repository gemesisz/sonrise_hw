package com.sonrise.alerting.notification;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final RetryPolicy policy = new RetryPolicy(4,
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15)));

    @Test
    void waitsLongerAfterEachFailedAttempt() {
        assertThat(policy.nextAttemptAt(1, NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(policy.nextAttemptAt(2, NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(policy.nextAttemptAt(3, NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void givesUpWhenMaxAttemptsReached() {
        assertThat(policy.nextAttemptAt(4, NOW)).isNull();
        assertThat(policy.nextAttemptAt(5, NOW)).isNull();
    }

    @Test
    void repeatsLastDelayWhenBackoffListIsShort() {
        RetryPolicy longPolicy = new RetryPolicy(6, List.of(Duration.ofMinutes(1), Duration.ofMinutes(5)));

        assertThat(longPolicy.nextAttemptAt(4, NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(longPolicy.nextAttemptAt(6, NOW)).isNull();
    }

    @Test
    void singleAttemptMeansNoRetries() {
        assertThat(new RetryPolicy(1, List.of()).nextAttemptAt(1, NOW)).isNull();
    }

    @Test
    void rejectsNonsensicalConfiguration() {
        assertThatThrownBy(() -> new RetryPolicy(0, List.of(Duration.ofMinutes(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
