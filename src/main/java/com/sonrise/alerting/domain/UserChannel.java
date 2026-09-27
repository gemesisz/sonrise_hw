package com.sonrise.alerting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/**
 * Links a user to a channel with the channel-specific {@link #address}
 * (email address, Slack webhook URL, ...). No address means no link.
 */
@Entity
@Table(name = "user_channel")
public class UserChannel {

    @EmbeddedId
    private UserChannelId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @MapsId("channelId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id")
    private Channel channel;

    @Column(nullable = false, length = 500)
    private String address;

    @Column(nullable = false)
    private boolean enabled = true;

    protected UserChannel() {
    }

    public UserChannel(AppUser user, Channel channel, String address) {
        this.id = new UserChannelId(user.getId(), channel.getId());
        this.user = user;
        this.channel = channel;
        this.address = address;
    }

    public UserChannelId getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
