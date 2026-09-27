package com.sonrise.alerting.source;

import com.sonrise.alerting.domain.Severity;

import java.time.Instant;
import java.util.Objects;

/**
 * An event as a source parsed it, before it is stored. The source decides the category
 * and the severity; {@code externalId} is the source's own id, used for dedup.
 */
public record EventCandidate(
        String categoryCode,
        String externalId,
        String title,
        String description,
        String url,
        Severity severity,
        Instant occurredAt) {

    public EventCandidate {
        Objects.requireNonNull(categoryCode, "categoryCode");
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
