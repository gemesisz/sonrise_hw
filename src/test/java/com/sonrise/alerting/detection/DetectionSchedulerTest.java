package com.sonrise.alerting.detection;

import com.sonrise.alerting.source.AbstractEventSource;
import com.sonrise.alerting.source.DetectionResult;
import com.sonrise.alerting.source.EventSourceException;
import com.sonrise.alerting.testsupport.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DetectionSchedulerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-27T10:00:00Z"));

    @Test
    void tickRunsEnabledSourcesAndSkipsDisabledOnes() {
        AbstractEventSource<?> usgs = source("USGS", true, Duration.ofMinutes(5));
        AbstractEventSource<?> rss = source("RSS", false, Duration.ofMinutes(5));

        new DetectionScheduler(List.of(usgs, rss), clock).tick();

        verify(usgs).detect();
        verify(rss, never()).detect();
    }

    @Test
    void tickWaitsForEachSourcesOwnInterval() {
        AbstractEventSource<?> fast = source("FAKE", true, Duration.ofSeconds(15));
        AbstractEventSource<?> slow = source("USGS", true, Duration.ofMinutes(5));
        DetectionScheduler scheduler = new DetectionScheduler(List.of(fast, slow), clock);

        scheduler.tick();                      // first tick: both are due
        clock.advance(Duration.ofSeconds(14));
        scheduler.tick();                      // nothing due yet
        clock.advance(Duration.ofSeconds(1));
        scheduler.tick();                      // FAKE due exactly at its interval
        clock.advance(Duration.ofMinutes(5));
        scheduler.tick();                      // both due again

        verify(fast, times(3)).detect();
        verify(slow, times(2)).detect();
    }

    @Test
    void runNowIgnoresIntervalsButNotTheEnabledFlag() {
        AbstractEventSource<?> usgs = source("USGS", true, Duration.ofMinutes(5));
        AbstractEventSource<?> rss = source("RSS", false, Duration.ofMinutes(5));
        DetectionScheduler scheduler = new DetectionScheduler(List.of(usgs, rss), clock);
        scheduler.tick();

        List<SourceRun> runs = scheduler.runNow();

        assertThat(runs).extracting(SourceRun::source, SourceRun::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("USGS", SourceRun.Status.COMPLETED));
        verify(usgs, times(2)).detect();
        verify(rss, never()).detect();
    }

    @Test
    void failingSourceDoesNotStopTheOthersAndStillWaitsItsInterval() {
        AbstractEventSource<?> broken = source("RSS", true, Duration.ofMinutes(10));
        AbstractEventSource<?> healthy = source("USGS", true, Duration.ofMinutes(5));
        when(broken.detect()).thenThrow(new EventSourceException("RSS: fetch failed: timeout", null));
        DetectionScheduler scheduler = new DetectionScheduler(List.of(broken, healthy), clock);

        List<SourceRun> runs = scheduler.runNow();
        clock.advance(Duration.ofMinutes(1));
        scheduler.tick();

        assertThat(runs.get(0).status()).isEqualTo(SourceRun.Status.FAILED);
        assertThat(runs.get(0).error()).contains("timeout");
        assertThat(runs.get(1).status()).isEqualTo(SourceRun.Status.COMPLETED);
        verify(broken, times(1)).detect(); // not hammered on every tick after failing
    }

    @Test
    void sameSourceNeverRunsTwiceAtTheSameTime() throws Exception {
        AbstractEventSource<?> usgs = source("USGS", true, Duration.ofMinutes(5));
        CountDownLatch insideDetect = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(usgs.detect()).thenAnswer(invocation -> {
            insideDetect.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new DetectionResult("USGS", 0, 0, 0, 0);
        });
        DetectionScheduler scheduler = new DetectionScheduler(List.of(usgs), clock);

        CompletableFuture<Void> scheduled = CompletableFuture.runAsync(scheduler::tick);
        assertThat(insideDetect.await(5, TimeUnit.SECONDS)).isTrue();
        List<SourceRun> manual = scheduler.runNow();   // while the scheduled run is still busy
        release.countDown();
        scheduled.get(5, TimeUnit.SECONDS);

        assertThat(manual).singleElement()
                .extracting(SourceRun::status).isEqualTo(SourceRun.Status.SKIPPED_ALREADY_RUNNING);
        verify(usgs, times(1)).detect();
        assertThat(scheduler.runNow()).singleElement()   // lock released afterwards
                .extracting(SourceRun::status).isEqualTo(SourceRun.Status.COMPLETED);
    }

    private static AbstractEventSource<?> source(String code, boolean enabled, Duration interval) {
        AbstractEventSource<?> source = mock(AbstractEventSource.class);
        when(source.code()).thenReturn(code);
        when(source.isEnabled()).thenReturn(enabled);
        when(source.interval()).thenReturn(interval);
        when(source.detect()).thenReturn(new DetectionResult(code, 0, 0, 0, 0));
        return source;
    }
}
