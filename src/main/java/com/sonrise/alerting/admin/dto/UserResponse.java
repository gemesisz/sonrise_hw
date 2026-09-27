package com.sonrise.alerting.admin.dto;

import java.time.Instant;
import java.util.List;

public record UserResponse(Long id, String name, Instant createdAt,
                           List<SubscriptionResponse> subscriptions, List<ChannelLinkResponse> channels) {
}
