package com.sonrise.alerting.admin.dto;

import com.sonrise.alerting.domain.Severity;
import jakarta.validation.constraints.NotNull;

public record SubscriptionRequest(@NotNull Severity minSeverity) {
}
