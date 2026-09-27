package com.sonrise.alerting.domain;

public enum NotificationStatus {
    PENDING,
    SENT,
    /** Delivery failed; will be retried until the attempt limit is reached. */
    FAILED,
    /** Attempt limit reached; no more automatic retries. */
    FAILED_PERMANENTLY
}
