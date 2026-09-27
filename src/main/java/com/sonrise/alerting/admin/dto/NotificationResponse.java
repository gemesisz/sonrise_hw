package com.sonrise.alerting.admin.dto;

import com.sonrise.alerting.domain.NotificationStatus;

import java.time.Instant;

public record NotificationResponse(Long id, Long eventId, String eventTitle, Long userId, String userName,
                                   String channel, NotificationStatus status, int attempts, String lastError,
                                   Instant nextAttemptAt, Instant sentAt, Instant createdAt) {
}
