package com.sonrise.alerting.admin;

import com.sonrise.alerting.admin.dto.FakeEventRequest;
import com.sonrise.alerting.admin.dto.SourceResponse;
import com.sonrise.alerting.admin.dto.SourceRunResponse;
import com.sonrise.alerting.detection.DetectionScheduler;
import com.sonrise.alerting.repository.CategoryRepository;
import com.sonrise.alerting.source.EventCandidate;
import com.sonrise.alerting.source.FakeEventSource;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class DetectionAdminService {

    private final DetectionScheduler scheduler;
    private final FakeEventSource fakeSource;
    private final CategoryRepository categories;
    private final Clock clock;

    public DetectionAdminService(DetectionScheduler scheduler, FakeEventSource fakeSource,
                                 CategoryRepository categories, Clock clock) {
        this.scheduler = scheduler;
        this.fakeSource = fakeSource;
        this.categories = categories;
        this.clock = clock;
    }

    public List<SourceResponse> sources() {
        return scheduler.sources().stream()
                .map(s -> new SourceResponse(s.code(), s.enabled(), s.interval(), s.lastStartedAt()))
                .toList();
    }

    public List<SourceRunResponse> runNow() {
        return scheduler.runNow().stream().map(SourceRunResponse::of).toList();
    }

    /**
     * Injects a test event and runs the fake source right away, so it flows through the real
     * pipeline (store → dispatcher → channels) like any detected event.
     */
    public SourceRunResponse injectFakeEvent(FakeEventRequest request) {
        if (!fakeSource.isEnabled()) {
            throw new ConflictException("The fake event source is disabled (alerting.sources.fake.enabled)");
        }
        if (categories.findByCode(request.category()).isEmpty()) {
            throw new BadRequestException("Unknown category " + request.category());
        }
        fakeSource.inject(new EventCandidate(request.category(), "admin-" + UUID.randomUUID(),
                request.title().trim(), request.description(), request.url(), request.severity(), clock.instant()));
        return SourceRunResponse.of(scheduler.runNow(FakeEventSource.CODE));
    }
}
