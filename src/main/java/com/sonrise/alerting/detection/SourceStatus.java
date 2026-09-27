package com.sonrise.alerting.detection;

import java.time.Duration;
import java.time.Instant;

public record SourceStatus(String code, boolean enabled, Duration interval, Instant lastStartedAt) {
}
