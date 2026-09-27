package com.sonrise.alerting.admin;

import com.sonrise.alerting.admin.dto.NotificationResponse;
import com.sonrise.alerting.admin.dto.PageResponse;
import com.sonrise.alerting.domain.NotificationStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/notifications")
public class NotificationAdminController {

    private final NotificationAdminService service;

    public NotificationAdminController(NotificationAdminService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<NotificationResponse> search(@RequestParam(required = false) NotificationStatus status,
                                                     @RequestParam(required = false) Long userId,
                                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.search(status, userId, page, size);
    }

    /** Only for FAILED notifications (409 otherwise). */
    @PostMapping("/{id}/retry")
    public NotificationResponse retryNow(@PathVariable Long id) {
        return service.retryNow(id);
    }
}
