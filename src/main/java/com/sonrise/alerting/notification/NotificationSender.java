package com.sonrise.alerting.notification;

import com.sonrise.alerting.channel.NotificationChannelRegistry;
import com.sonrise.alerting.domain.Event;
import com.sonrise.alerting.domain.Notification;
import com.sonrise.alerting.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * Sends one notification and records the outcome. Shared by the first delivery
 * ({@link NotificationDispatcher}) and retries ({@link NotificationRetryJob}).
 */
@Component
public class NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    private final NotificationRepository notificationRepository;
    private final NotificationChannelRegistry channelRegistry;
    private final RetryPolicy retryPolicy;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public NotificationSender(NotificationRepository notificationRepository,
                              NotificationChannelRegistry channelRegistry,
                              RetryPolicy retryPolicy,
                              PlatformTransactionManager transactionManager,
                              Clock clock) {
        this.notificationRepository = notificationRepository;
        this.channelRegistry = channelRegistry;
        this.retryPolicy = retryPolicy;
        this.transactions = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW: the dispatcher calls this from an AFTER_COMMIT listener, where a default
        // (REQUIRED) template would join the already-committed transaction (D33).
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * Sends with no transaction open (network call), then records the outcome in its own
     * transaction. Never throws: a failure is recorded, and the caller moves on to the next one.
     *
     * @param event detached, with its category loaded
     */
    public void send(Long notificationId, String channelCode, String address, Event event) {
        String error = null;
        try {
            channelRegistry.get(channelCode).send(address, event);
        } catch (RuntimeException e) {
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
        }
        String failure = error;
        transactions.executeWithoutResult(status -> {
            Notification notification = notificationRepository.findById(notificationId).orElseThrow();
            Instant now = clock.instant();
            if (failure == null) {
                notification.markSent(now);
                return;
            }
            Instant retryAt = retryPolicy.nextAttemptAt(notification.getAttempts() + 1, now);
            notification.markFailed(failure, retryAt);
            if (retryAt == null) {
                log.warn("Notification {} via {} failed for good after {} attempt(s): {}",
                        notificationId, channelCode, notification.getAttempts(), failure);
            } else {
                log.warn("Notification {} via {} failed (attempt {}), retry at {}: {}",
                        notificationId, channelCode, notification.getAttempts(), retryAt, failure);
            }
        });
    }

    /**
     * Gives up on a notification without sending it.
     */
    public void abandon(Long notificationId, String reason) {
        transactions.executeWithoutResult(status ->
                notificationRepository.findById(notificationId).orElseThrow().abandon(reason));
        log.warn("Notification {} abandoned: {}", notificationId, reason);
    }
}
