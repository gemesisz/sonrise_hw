package com.sonrise.alerting.domain;

import java.util.Arrays;
import java.util.List;

/**
 * How important an event is. Declaration order matters: from least to most severe.
 */
public enum Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /**
     * The minimum severities a subscription can have and still be notified of an event
     * of this severity: this one and every less severe one.
     */
    public List<Severity> andBelow() {
        return Arrays.stream(values()).filter(s -> s.compareTo(this) <= 0).toList();
    }
}
