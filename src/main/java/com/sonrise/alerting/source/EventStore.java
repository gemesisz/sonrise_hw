package com.sonrise.alerting.source;

import com.sonrise.alerting.domain.Category;
import com.sonrise.alerting.domain.Event;
import com.sonrise.alerting.notification.EventDetected;
import com.sonrise.alerting.repository.CategoryRepository;
import com.sonrise.alerting.repository.EventRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Turns a candidate into a stored {@link Event}, skipping ones already stored.
 * Each candidate is saved in its own transaction, so one bad item never loses the others.
 */
@Component
public class EventStore {

    // Must match the column lengths in 006-create-event.yaml.
    static final int MAX_EXTERNAL_ID = 255;
    static final int MAX_TITLE = 500;
    static final int MAX_DESCRIPTION = 4000;
    static final int MAX_URL = 1000;

    private final EventRepository eventRepository;
    private final CategoryRepository categoryRepository;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    public EventStore(EventRepository eventRepository, CategoryRepository categoryRepository, Clock clock,
                      ApplicationEventPublisher eventPublisher) {
        this.eventRepository = eventRepository;
        this.categoryRepository = categoryRepository;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    /**
     * @return {@code true} if a new event was stored, {@code false} if it already existed
     * @throws RejectedCandidateException if the candidate cannot be stored
     */
    @Transactional
    public boolean saveIfNew(String source, EventCandidate candidate) {
        // Truncating the id could merge two different events, so reject instead.
        if (candidate.externalId().length() > MAX_EXTERNAL_ID) {
            throw new RejectedCandidateException("externalId longer than " + MAX_EXTERNAL_ID + " characters");
        }
        if (eventRepository.existsBySourceAndExternalId(source, candidate.externalId())) {
            return false;
        }
        Category category = categoryRepository.findByCode(candidate.categoryCode())
                .orElseThrow(() -> new RejectedCandidateException("Unknown category " + candidate.categoryCode()));

        Event event = eventRepository.save(new Event(
                category,
                source,
                candidate.externalId(),
                truncate(candidate.title(), MAX_TITLE),
                truncate(candidate.description(), MAX_DESCRIPTION),
                // A cut-off URL is a broken link: drop it rather than store it.
                candidate.url() != null && candidate.url().length() > MAX_URL ? null : candidate.url(),
                candidate.severity(),
                candidate.occurredAt(),
                clock.instant()));
        // Observer: listeners are called only after this transaction commits
        // (@TransactionalEventListener), so nobody is notified of an event that failed to save.
        eventPublisher.publishEvent(new EventDetected(event.getId()));
        return true;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 1) + "…";
    }
}
