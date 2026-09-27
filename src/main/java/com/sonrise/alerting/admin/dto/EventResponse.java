package com.sonrise.alerting.admin.dto;

import com.sonrise.alerting.domain.Severity;

import java.time.Instant;

public record EventResponse(Long id, String category, String source, String externalId, String title,
                            String description, String url, Severity severity,
                            Instant occurredAt, Instant detectedAt) {
}
