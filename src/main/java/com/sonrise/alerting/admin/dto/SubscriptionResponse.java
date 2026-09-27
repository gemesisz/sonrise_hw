package com.sonrise.alerting.admin.dto;

import com.sonrise.alerting.domain.Severity;

public record SubscriptionResponse(String category, Severity minSeverity) {
}
