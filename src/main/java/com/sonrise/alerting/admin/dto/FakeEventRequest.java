package com.sonrise.alerting.admin.dto;

import com.sonrise.alerting.domain.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A test event for the fake source (demo). Occurs "now"; its id is generated.
 */
public record FakeEventRequest(@NotBlank String category,
                               @NotBlank @Size(max = 500) String title,
                               @Size(max = 4000) String description,
                               @Size(max = 1000) String url,
                               @NotNull Severity severity) {
}
