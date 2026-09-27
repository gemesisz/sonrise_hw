package com.sonrise.alerting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A world event detected by an event source. {@code (source, externalId)} is unique,
 * so polling the same feed twice never stores the same event twice.
 */
@Entity
@Table(name = "event", uniqueConstraints = @UniqueConstraint(
        name = "uq_event_source_external_id", columnNames = {"source", "external_id"}))
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, length = 50)
    private String source;

    @Column(name = "external_id", nullable = false, length = 255)
    private String externalId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 4000)
    private String description;

    @Column(length = 1000)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    protected Event() {
    }

    public Event(Category category, String source, String externalId, String title, String description,
                 String url, Severity severity, Instant occurredAt, Instant detectedAt) {
        this.category = category;
        this.source = source;
        this.externalId = externalId;
        this.title = title;
        this.description = description;
        this.url = url;
        this.severity = severity;
        this.occurredAt = occurredAt;
        this.detectedAt = detectedAt;
    }

    public Long getId() {
        return id;
    }

    public Category getCategory() {
        return category;
    }

    public String getSource() {
        return source;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getUrl() {
        return url;
    }

    public Severity getSeverity() {
        return severity;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }
}
