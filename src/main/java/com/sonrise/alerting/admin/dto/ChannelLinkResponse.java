package com.sonrise.alerting.admin.dto;

/**
 * @param address as shown to the admin; secrets (Slack webhook URLs) are masked
 */
public record ChannelLinkResponse(String channel, String address, boolean enabled) {
}
