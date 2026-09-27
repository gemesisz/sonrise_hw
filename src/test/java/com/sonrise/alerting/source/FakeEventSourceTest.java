package com.sonrise.alerting.source;

import com.sonrise.alerting.domain.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FakeEventSourceTest {

    private final EventStore store = mock(EventStore.class);
    private final FakeEventSource source = new FakeEventSource(true, store);

    @Test
    void injectedEventsArePickedUpOnceByTheNextRun() {
        EventCandidate candidate = new EventCandidate("MARKETS", "demo-1", "Demo crash", null, null,
                Severity.CRITICAL, Instant.EPOCH);
        when(store.saveIfNew(eq("FAKE"), any())).thenReturn(true);
        source.inject(candidate);

        DetectionResult first = source.detect();
        DetectionResult second = source.detect();

        verify(store).saveIfNew("FAKE", candidate);
        assertThat(first.created()).isEqualTo(1);
        assertThat(second.found()).isZero();
    }
}
