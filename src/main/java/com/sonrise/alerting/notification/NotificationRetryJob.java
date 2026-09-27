package com.sonrise.alerting.notification;

import com.sonrise.alerting.domain.Event;
import com.sonrise.alerting.domain.Notification;
import com.sonrise.alerting.domain.NotificationStatus;
import com.sonrise.alerting.domain.UserChannelId;
import com.sonrise.alerting.repository.NotificationRepository;
import com.sonrise.alerting.repository.UserChannelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * Re-sends FAILED notifications whose next attempt is due. Uses the user's *current* channel
 * link, so a corrected address is picked up; a removed or disabled link ends the retries.
 */
@Component
public class NotificationRetryJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetryJob.class);

    private final NotificationRepository notificationRepository;
    private final UserChannelRepository userChannelRepository;
    private final NotificationSender sender;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;

    public NotificationRetryJob(NotificationRepository notificationRepository,
                                UserChannelRepository userChannelRepository,
                                NotificationSender sender,
                                PlatformTransactionManager transactionManager,
                                Clock clock,
                                @Value("${alerting.notification.retry.batch-size}") int batchSize) {
        this.notificationRepository = notificationRepository;
        this.userChannelRepository = userChannelRepository;
        this.sender = sender;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * @return how many notifications were picked up
     */
    @Scheduled(fixedDelayString = "${alerting.notification.retry.tick}")
    public int retryDue() {
        List<Retry> due = transactions.execute(status -> notificationRepository
                .findDue(NotificationStatus.FAILED, clock.instant(), PageRequest.of(0, batchSize))
                .stream()
                .map(this::toRetry)
                .toList());
        due.forEach(this::send);
        if (!due.isEmpty()) {
            log.info("Retried {} notification(s)", due.size());
        }
        return due.size();
    }

    /**
     * Retries one FAILED notification immediately (admin "retry now"), ignoring its due time.
     *
     * @return {@code false} if it doesn't exist or is not FAILED (nothing was sent)
     */
    public boolean retryNow(Long notificationId) {
        Retry retry = transactions.execute(status -> notificationRepository.findWithDetailsById(notificationId)
                .filter(n -> n.getStatus() == NotificationStatus.FAILED)
                .map(this::toRetry)
                .orElse(null));
        if (retry == null) {
            return false;
        }
        send(retry);
        return true;
    }

    private void send(Retry retry) {
        if (retry.address() == null) {
            sender.abandon(retry.notificationId(), "Channel link removed or disabled; not retried");
        } else {
            sender.send(retry.notificationId(), retry.channelCode(), retry.address(), retry.event());
        }
    }

    private Retry toRetry(Notification notification) {
        String address = userChannelRepository
                .findById(new UserChannelId(notification.getUser().getId(), notification.getChannel().getId()))
                .filter(link -> link.isEnabled() && link.getChannel().isEnabled())
                .map(link -> link.getAddress())
                .orElse(null);
        return new Retry(notification.getId(), notification.getChannel().getCode(), address, notification.getEvent());
    }

    private record Retry(Long notificationId, String channelCode, String address, Event event) {
    }
}
