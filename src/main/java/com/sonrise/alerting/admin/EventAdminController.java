package com.sonrise.alerting.admin;

import com.sonrise.alerting.admin.dto.EventResponse;
import com.sonrise.alerting.admin.dto.PageResponse;
import com.sonrise.alerting.domain.Severity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/events")
public class EventAdminController {

    private final EventAdminService service;

    public EventAdminController(EventAdminService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<EventResponse> search(@RequestParam(required = false) String category,
                                              @RequestParam(required = false) String source,
                                              @RequestParam(required = false) Severity minSeverity,
                                              @RequestParam(defaultValue = "0") @Min(0) int page,
                                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.search(category, source, minSeverity, page, size);
    }
}
