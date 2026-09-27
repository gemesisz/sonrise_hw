package com.sonrise.alerting.source;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Deterministic source for tests and demos: events are injected (later by an admin
 * endpoint) and picked up on the next detection run, like any real source.
 */
@Component
public class FakeEventSource extends AbstractEventSource<List<EventCandidate>> {

    public static final String CODE = "FAKE";

    private final Queue<EventCandidate> pending = new ConcurrentLinkedQueue<>();

    public FakeEventSource(@Value("${alerting.sources.fake.enabled}") boolean enabled,
                           @Value("${alerting.sources.fake.interval}") Duration interval,
                           EventStore eventStore) {
        super(CODE, enabled, interval, eventStore);
    }

    public void inject(EventCandidate candidate) {
        pending.add(candidate);
    }

    @Override
    protected List<EventCandidate> fetch() {
        List<EventCandidate> drained = new ArrayList<>();
        EventCandidate next;
        while ((next = pending.poll()) != null) {
            drained.add(next);
        }
        return drained;
    }

    @Override
    protected List<EventCandidate> parse(List<EventCandidate> raw) {
        return raw;
    }
}
