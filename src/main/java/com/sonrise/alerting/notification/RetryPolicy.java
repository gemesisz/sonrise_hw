package com.sonrise.alerting.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Decides whether and when a failed notification is retried.
 * {@code max-attempts} counts every attempt, the first send included.
 */
@Component
public class RetryPolicy {

    private final int maxAttempts;
    private final List<Duration> backoff;

    public RetryPolicy(@Value("${alerting.notification.retry.max-attempts}") int maxAttempts,
                       @Value("${alerting.notification.retry.backoff}") List<Duration> backoff) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("alerting.notification.retry.max-attempts must be at least 1");
        }
        if (maxAttempts > 1 && backoff.isEmpty()) {
            throw new IllegalArgumentException("alerting.notification.retry.backoff needs at least one delay");
        }
        this.maxAttempts = maxAttempts;
        this.backoff = List.copyOf(backoff);
    }

    /**
     * @param attemptsSoFar attempts made, including the one that just failed
     * @return when to try again, or {@code null} if the limit is reached
     */
    public Instant nextAttemptAt(int attemptsSoFar, Instant now) {
        if (attemptsSoFar >= maxAttempts) {
            return null;
        }
        // After attempt n, wait backoff[n-1]; if the list is shorter than needed, keep the last delay.
        Duration delay = backoff.get(Math.min(attemptsSoFar, backoff.size()) - 1);
        return now.plus(delay);
    }
}
