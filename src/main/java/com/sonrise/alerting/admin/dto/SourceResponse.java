package com.sonrise.alerting.admin.dto;

import java.time.Duration;
import java.time.Instant;

public record SourceResponse(String code, boolean enabled, Duration interval, Instant lastStartedAt) {
}
