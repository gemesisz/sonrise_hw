package com.sonrise.alerting.source;

import com.sonrise.alerting.domain.Event;
import com.sonrise.alerting.domain.Severity;
import com.sonrise.alerting.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({EventStore.class, EventStoreTest.FixedClock.class})
class EventStoreTest {

    static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private EventStore store;

    @Autowired
    private EventRepository events;

    @Test
    void storesNewEventWithCategoryAndDetectionTime() {
        Instant occurred = Instant.parse("2026-09-27T08:00:00Z");

        boolean created = store.saveIfNew("USGS", new EventCandidate("NATURAL_DISASTERS", "us1", "M 6.1",
                "desc", "https://example.com/us1", Severity.HIGH, occurred));

        assertThat(created).isTrue();
        Event event = events.findAll().getFirst();
        assertThat(event.getSource()).isEqualTo("USGS");
        assertThat(event.getExternalId()).isEqualTo("us1");
        assertThat(event.getCategory().getCode()).isEqualTo("NATURAL_DISASTERS");
        assertThat(event.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(event.getOccurredAt()).isEqualTo(occurred);
        assertThat(event.getDetectedAt()).isEqualTo(NOW);
    }

    @Test
    void skipsEventAlreadyStoredFromSameSource() {
        EventCandidate candidate = candidate("us1");
        assertThat(store.saveIfNew("USGS", candidate)).isTrue();

        assertThat(store.saveIfNew("USGS", candidate)).isFalse();
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void sameExternalIdFromAnotherSourceIsANewEvent() {
        store.saveIfNew("USGS", candidate("42"));

        assertThat(store.saveIfNew("RSS", candidate("42"))).isTrue();
    }

    @Test
    void rejectsUnknownCategory() {
        EventCandidate candidate = new EventCandidate("SPORTS", "x", "t", null, null, Severity.LOW, NOW);

        assertThatThrownBy(() -> store.saveIfNew("FAKE", candidate))
                .isInstanceOf(RejectedCandidateException.class)
                .hasMessageContaining("SPORTS");
        assertThat(events.count()).isZero();
    }

    @Test
    void rejectsExternalIdTooLongForColumnInsteadOfTruncating() {
        EventCandidate candidate = candidate("x".repeat(EventStore.MAX_EXTERNAL_ID + 1));

        assertThatThrownBy(() -> store.saveIfNew("FAKE", candidate))
                .isInstanceOf(RejectedCandidateException.class);
    }

    @Test
    void truncatesOverlongTextAndDropsOverlongUrl() {
        EventCandidate candidate = new EventCandidate("BREAKING_NEWS", "long", "t".repeat(600),
                "d".repeat(5000), "https://example.com/" + "p".repeat(1000), Severity.LOW, NOW);

        store.saveIfNew("RSS", candidate);
        events.flush();

        Event event = events.findAll().getFirst();
        assertThat(event.getTitle()).hasSize(EventStore.MAX_TITLE).endsWith("…");
        assertThat(event.getDescription()).hasSize(EventStore.MAX_DESCRIPTION);
        assertThat(event.getUrl()).isNull();
    }

    private static EventCandidate candidate(String id) {
        return new EventCandidate("NATURAL_DISASTERS", id, "title", null, null, Severity.LOW, NOW);
    }
}
