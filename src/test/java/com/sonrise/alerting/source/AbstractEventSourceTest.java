package com.sonrise.alerting.source;

import com.sonrise.alerting.domain.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbstractEventSourceTest {

    @Mock
    private EventStore eventStore;

    @Test
    void storesEachCandidateAndCountsOutcomes() {
        EventCandidate fresh = candidate("1");
        EventCandidate seen = candidate("2");
        EventCandidate bad = candidate("3");
        when(eventStore.saveIfNew("TEST", fresh)).thenReturn(true);
        when(eventStore.saveIfNew("TEST", seen)).thenReturn(false);
        when(eventStore.saveIfNew("TEST", bad)).thenThrow(new RejectedCandidateException("Unknown category"));

        DetectionResult result = source(() -> "raw", raw -> List.of(fresh, seen, bad)).detect();

        assertThat(result).isEqualTo(new DetectionResult("TEST", 3, 1, 1, 1));
    }

    @Test
    void passesFetchedPayloadToParse() {
        StubSource source = source(() -> "payload", raw -> {
            assertThat(raw).isEqualTo("payload");
            return List.of(candidate("1"));
        });
        when(eventStore.saveIfNew(eq("TEST"), any())).thenReturn(true);

        source.detect();

        verify(eventStore).saveIfNew(eq("TEST"), any());
    }

    @Test
    void fetchFailureStoresNothing() {
        StubSource source = source(() -> {
            throw new IllegalStateException("connection refused");
        }, raw -> List.of());

        assertThatThrownBy(source::detect)
                .isInstanceOf(EventSourceException.class)
                .hasMessageContaining("TEST: fetch failed: connection refused");
        verifyNoInteractions(eventStore);
    }

    @Test
    void parseFailureStoresNothing() {
        StubSource source = source(() -> "garbage", raw -> {
            throw new IllegalArgumentException("not JSON");
        });

        assertThatThrownBy(source::detect)
                .isInstanceOf(EventSourceException.class)
                .hasMessageContaining("TEST: parse failed: not JSON");
        verifyNoInteractions(eventStore);
    }

    private StubSource source(Supplier<String> fetch, Function<String, List<EventCandidate>> parse) {
        return new StubSource(eventStore, fetch, parse);
    }

    private static EventCandidate candidate(String id) {
        return new EventCandidate("MARKETS", id, "title " + id, null, null, Severity.LOW, Instant.EPOCH);
    }

    private static final class StubSource extends AbstractEventSource<String> {

        private final Supplier<String> fetch;
        private final Function<String, List<EventCandidate>> parse;

        StubSource(EventStore store, Supplier<String> fetch, Function<String, List<EventCandidate>> parse) {
            super("TEST", true, store);
            this.fetch = fetch;
            this.parse = parse;
        }

        @Override
        protected String fetch() {
            return fetch.get();
        }

        @Override
        protected List<EventCandidate> parse(String raw) {
            return parse.apply(raw);
        }
    }
}
