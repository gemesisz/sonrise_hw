package com.sonrise.alerting.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserRequest(@NotBlank @Size(max = 100) String name) {
}
