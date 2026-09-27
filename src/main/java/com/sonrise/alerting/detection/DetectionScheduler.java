package com.sonrise.alerting.detection;

import com.sonrise.alerting.source.AbstractEventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs event detection. One job for all sources: every tick it runs each enabled source whose
 * own interval has elapsed. {@link #runNow()} runs every enabled source immediately (admin trigger).
 *
 * <p>Overlap guard: a source never runs twice at the same time (e.g. a scheduled tick and a
 * manual trigger). The second attempt is skipped, not queued. This also keeps the
 * check-then-insert dedup in {@code EventStore} safe (D27).
 */
@Component
public class DetectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DetectionScheduler.class);

    private final List<AbstractEventSource<?>> sources;
    private final Clock clock;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastStarted = new ConcurrentHashMap<>();

    public DetectionScheduler(List<AbstractEventSource<?>> sources, Clock clock) {
        this.sources = sources;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${alerting.detection.tick}")
    public void tick() {
        for (AbstractEventSource<?> source : sources) {
            if (source.isEnabled() && isDue(source)) {
                run(source);
            }
        }
    }

    /**
     * Runs every enabled source now, regardless of its interval.
     */
    public List<SourceRun> runNow() {
        return sources.stream()
                .filter(AbstractEventSource::isEnabled)
                .map(this::run)
                .toList();
    }

    /**
     * Runs one enabled source now.
     *
     * @throws IllegalArgumentException if there is no such source
     * @throws IllegalStateException    if the source is disabled
     */
    public SourceRun runNow(String code) {
        AbstractEventSource<?> source = sources.stream()
                .filter(s -> s.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No event source " + code));
        if (!source.isEnabled()) {
            throw new IllegalStateException("Event source " + code + " is disabled");
        }
        return run(source);
    }

    /**
     * Every source with its configuration and when it last started (null = not yet run).
     */
    public List<SourceStatus> sources() {
        return sources.stream()
                .map(s -> new SourceStatus(s.code(), s.isEnabled(), s.interval(), lastStarted.get(s.code())))
                .toList();
    }

    private boolean isDue(AbstractEventSource<?> source) {
        Instant last = lastStarted.get(source.code());
        return last == null || !clock.instant().isBefore(last.plus(source.interval()));
    }

    private SourceRun run(AbstractEventSource<?> source) {
        ReentrantLock lock = locks.computeIfAbsent(source.code(), code -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.info("{}: already running, skipped", source.code());
            return SourceRun.skipped(source.code());
        }
        try {
            // Recorded before running, so a failing source also waits its interval instead of
            // being retried on every tick.
            lastStarted.put(source.code(), clock.instant());
            return SourceRun.completed(source.detect());
        } catch (RuntimeException e) {
            // One broken source must not stop the others.
            log.warn("{}: detection failed: {}", source.code(), e.getMessage(), e);
            return SourceRun.failed(source.code(), e.getMessage());
        } finally {
            lock.unlock();
        }
    }
}
