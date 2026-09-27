package com.sonrise.alerting.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * Template Method for event sources. {@link #detect()} fixes the sequence
 * fetch → parse → (per candidate) skip duplicates → save; a source only implements
 * {@link #fetch()} and {@link #parse(Object)}, and maps severity in {@code parse}.
 *
 * @param <T> the raw payload the source fetches (e.g. a response body)
 */
public abstract class AbstractEventSource<T> {

    private static final Logger log = LoggerFactory.getLogger(AbstractEventSource.class);

    private final String code;
    private final boolean enabled;
    private final Duration interval;
    private final EventStore eventStore;

    protected AbstractEventSource(String code, boolean enabled, Duration interval, EventStore eventStore) {
        this.code = code;
        this.enabled = enabled;
        this.interval = interval;
        this.eventStore = eventStore;
    }

    /**
     * Stored as {@code event.source}; together with the external id it identifies an event.
     */
    public final String code() {
        return code;
    }

    /**
     * From config ({@code alerting.sources.<name>.enabled}). The scheduler skips disabled sources.
     */
    public final boolean isEnabled() {
        return enabled;
    }

    /**
     * From config ({@code alerting.sources.<name>.interval}): minimum time between scheduled runs.
     */
    public final Duration interval() {
        return interval;
    }

    public final DetectionResult detect() {
        T raw;
        try {
            raw = fetch();
        } catch (RuntimeException e) {
            throw new EventSourceException(code + ": fetch failed: " + e.getMessage(), e);
        }
        List<EventCandidate> candidates;
        try {
            candidates = parse(raw);
        } catch (RuntimeException e) {
            throw new EventSourceException(code + ": parse failed: " + e.getMessage(), e);
        }

        int created = 0;
        int duplicates = 0;
        int rejected = 0;
        for (EventCandidate candidate : candidates) {
            try {
                if (eventStore.saveIfNew(code, candidate)) {
                    created++;
                } else {
                    duplicates++;
                }
            } catch (RejectedCandidateException e) {
                rejected++;
                log.warn("{}: rejected event {}: {}", code, candidate.externalId(), e.getMessage());
            }
        }
        DetectionResult result = new DetectionResult(code, candidates.size(), created, duplicates, rejected);
        log.info("{}: found {}, created {}, duplicates {}, rejected {}",
                code, result.found(), result.created(), result.duplicates(), result.rejected());
        return result;
    }

    /**
     * Gets the raw data from the source (HTTP call, queue, ...).
     */
    protected abstract T fetch();

    /**
     * Turns the raw data into candidates, assigning category and severity.
     * Items that cannot be interpreted are skipped, not failed.
     */
    protected abstract List<EventCandidate> parse(T raw);
}
