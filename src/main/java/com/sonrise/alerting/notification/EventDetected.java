package com.sonrise.alerting.notification;

/**
 * Published when a new event has been stored. Carries only the id: listeners load what
 * they need in their own transaction, after the storing transaction has committed.
 */
public record EventDetected(Long eventId) {
}
