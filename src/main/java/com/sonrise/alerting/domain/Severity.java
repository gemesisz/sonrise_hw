package com.sonrise.alerting.domain;

/**
 * How important an event is. Declaration order matters: from least to most severe.
 */
public enum Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
