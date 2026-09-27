package com.sonrise.alerting.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param address checked by the channel's own {@code validateAddress} as well
 * @param enabled optional, defaults to {@code true}
 */
public record ChannelLinkRequest(@NotBlank @Size(max = 500) String address, Boolean enabled) {
}
