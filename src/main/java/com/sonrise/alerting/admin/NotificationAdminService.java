package com.sonrise.alerting.admin;

import com.sonrise.alerting.admin.dto.NotificationResponse;
import com.sonrise.alerting.admin.dto.PageResponse;
import com.sonrise.alerting.domain.Notification;
import com.sonrise.alerting.domain.NotificationStatus;
import com.sonrise.alerting.notification.NotificationRetryJob;
import com.sonrise.alerting.repository.NotificationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class NotificationAdminService {

    private final NotificationRepository notifications;
    private final NotificationRetryJob retryJob;
    private final TransactionTemplate readOnly;

    public NotificationAdminService(NotificationRepository notifications, NotificationRetryJob retryJob,
                                    PlatformTransactionManager transactionManager) {
        this.notifications = notifications;
        this.retryJob = retryJob;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * Newest first. Both filters are optional.
     */
    public PageResponse<NotificationResponse> search(NotificationStatus status, Long userId, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return readOnly.execute(tx ->
                PageResponse.of(notifications.search(status, userId, pageable), NotificationAdminService::toResponse));
    }

    /**
     * Sends a FAILED notification immediately instead of waiting for its next attempt.
     * Not a transaction itself: sending happens outside any transaction (network call).
     */
    public NotificationResponse retryNow(Long id) {
        NotificationStatus status = readOnly.execute(tx -> notifications.findById(id)
                .map(Notification::getStatus)
                .orElseThrow(() -> new NotFoundException("Notification " + id + " not found")));
        if (status != NotificationStatus.FAILED) {
            // Admin decision (D43): SENT is done, FAILED_PERMANENTLY is final.
            throw new ConflictException("Only FAILED notifications can be retried; notification " + id + " is " + status);
        }
        if (!retryJob.retryNow(id)) {
            // The scheduled retry job got to it between the check and here.
            throw new ConflictException("Notification " + id + " is no longer FAILED");
        }
        return readOnly.execute(tx -> toResponse(notifications.findWithDetailsById(id).orElseThrow()));
    }

    private static NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(n.getId(), n.getEvent().getId(), n.getEvent().getTitle(),
                n.getUser().getId(), n.getUser().getName(), n.getChannel().getCode(), n.getStatus(),
                n.getAttempts(), n.getLastError(), n.getNextAttemptAt(), n.getSentAt(), n.getCreatedAt());
    }
}
