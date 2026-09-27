package com.sonrise.alerting.admin.dto;

import jakarta.validation.constraints.NotNull;

public record ChannelUpdateRequest(@NotNull Boolean enabled) {
}
