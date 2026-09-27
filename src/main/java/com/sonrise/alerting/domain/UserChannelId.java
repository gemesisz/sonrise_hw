package com.sonrise.alerting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class UserChannelId implements Serializable {

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "channel_id")
    private Long channelId;

    protected UserChannelId() {
    }

    public UserChannelId(Long userId, Long channelId) {
        this.userId = userId;
        this.channelId = channelId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getChannelId() {
        return channelId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserChannelId other)) {
            return false;
        }
        return Objects.equals(userId, other.userId) && Objects.equals(channelId, other.channelId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, channelId);
    }
}
