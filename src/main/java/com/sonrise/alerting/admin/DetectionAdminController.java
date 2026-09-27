package com.sonrise.alerting.admin;

import com.sonrise.alerting.admin.dto.FakeEventRequest;
import com.sonrise.alerting.admin.dto.SourceResponse;
import com.sonrise.alerting.admin.dto.SourceRunResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class DetectionAdminController {

    private final DetectionAdminService service;

    public DetectionAdminController(DetectionAdminService service) {
        this.service = service;
    }

    @GetMapping("/sources")
    public List<SourceResponse> sources() {
        return service.sources();
    }

    /** Runs every enabled source now (the manual trigger). */
    @PostMapping("/detection/run")
    public List<SourceRunResponse> runNow() {
        return service.runNow();
    }

    /** Demo: injects a test event and runs the fake source immediately. */
    @PostMapping("/fake-events")
    public SourceRunResponse injectFakeEvent(@Valid @RequestBody FakeEventRequest request) {
        return service.injectFakeEvent(request);
    }
}
