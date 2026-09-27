package com.sonrise.alerting.notification;

import com.sonrise.alerting.channel.NotificationChannelRegistry;
import com.sonrise.alerting.domain.Event;
import com.sonrise.alerting.domain.Notification;
import com.sonrise.alerting.domain.UserChannel;
import com.sonrise.alerting.repository.EventRepository;
import com.sonrise.alerting.repository.NotificationRepository;
import com.sonrise.alerting.repository.UserChannelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * Observer of {@link EventDetected}: fans a new event out to its category's subscribers.
 *
 * <p>Three short transactions instead of one long one: (1) create all notification rows as
 * PENDING, (2) send each one with no transaction open (network calls), (3) record each
 * outcome separately. A crash mid-way leaves PENDING/FAILED rows behind, never a message
 * that was sent but not recorded as a whole batch rolled back.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final EventRepository eventRepository;
    private final UserChannelRepository userChannelRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationChannelRegistry channelRegistry;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public NotificationDispatcher(EventRepository eventRepository,
                                  UserChannelRepository userChannelRepository,
                                  NotificationRepository notificationRepository,
                                  NotificationChannelRegistry channelRegistry,
                                  PlatformTransactionManager transactionManager,
                                  Clock clock) {
        this.eventRepository = eventRepository;
        this.userChannelRepository = userChannelRepository;
        this.notificationRepository = notificationRepository;
        this.channelRegistry = channelRegistry;
        this.transactions = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW is essential: an AFTER_COMMIT listener still sees the committed
        // transaction bound to the thread, and a default (REQUIRED) template would silently
        // join it, so nothing written here would ever be committed.
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventDetected(EventDetected detected) {
        // Runs in the detecting thread, right after the event's transaction committed.
        // Spring itself swallows exceptions from AFTER_COMMIT listeners (verified by a test with
        // this catch removed), so this is not what protects detection; it logs *which* event failed.
        try {
            dispatch(detected.eventId());
        } catch (RuntimeException e) {
            log.error("Dispatching event {} failed", detected.eventId(), e);
        }
    }

    void dispatch(Long eventId) {
        List<Delivery> deliveries = transactions.execute(status -> createNotifications(eventId));
        log.info("Event {}: {} notification(s) to send", eventId, deliveries.size());
        deliveries.forEach(this::deliver);
    }

    private List<Delivery> createNotifications(Long eventId) {
        Event event = eventRepository.findWithCategoryById(eventId)
                .orElseThrow(() -> new IllegalStateException("Event " + eventId + " not found"));
        List<UserChannel> targets = userChannelRepository.findDeliveryTargets(
                event.getCategory().getId(), event.getSeverity().andBelow());
        return targets.stream()
                .map(target -> new Delivery(
                        notificationRepository.save(new Notification(event, target.getUser(), target.getChannel())).getId(),
                        target.getChannel().getCode(),
                        target.getAddress(),
                        event))
                .toList();
    }

    private void deliver(Delivery delivery) {
        String error = null;
        try {
            channelRegistry.get(delivery.channelCode()).send(delivery.address(), delivery.event());
        } catch (RuntimeException e) {
            // One failed channel must not stop the others: record it and move on.
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.warn("Notification {} via {} failed: {}", delivery.notificationId(), delivery.channelCode(), error);
        }
        String failure = error;
        transactions.executeWithoutResult(status -> {
            Notification notification = notificationRepository.findById(delivery.notificationId()).orElseThrow();
            if (failure == null) {
                notification.markSent(clock.instant());
            } else {
                notification.markFailed(failure);
            }
        });
    }

    /**
     * Everything needed to send one notification outside a transaction.
     * {@code event} is detached but has its category loaded.
     */
    private record Delivery(Long notificationId, String channelCode, String address, Event event) {
    }
}
