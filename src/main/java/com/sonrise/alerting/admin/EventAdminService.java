package com.sonrise.alerting.admin;

import com.sonrise.alerting.admin.dto.EventResponse;
import com.sonrise.alerting.admin.dto.PageResponse;
import com.sonrise.alerting.domain.Event;
import com.sonrise.alerting.domain.Severity;
import com.sonrise.alerting.repository.EventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EventAdminService {

    private final EventRepository events;

    public EventAdminService(EventRepository events) {
        this.events = events;
    }

    /**
     * Newest first. Every filter is optional; {@code minSeverity} means "this or more severe".
     */
    public PageResponse<EventResponse> search(String category, String source, Severity minSeverity, int page, int size) {
        var severities = (minSeverity == null ? Severity.LOW : minSeverity).andAbove();
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("detectedAt"), Sort.Order.desc("id")));
        return PageResponse.of(events.search(category, source, severities, pageable), EventAdminService::toResponse);
    }

    private static EventResponse toResponse(Event e) {
        return new EventResponse(e.getId(), e.getCategory().getCode(), e.getSource(), e.getExternalId(),
                e.getTitle(), e.getDescription(), e.getUrl(), e.getSeverity(), e.getOccurredAt(), e.getDetectedAt());
    }
}
