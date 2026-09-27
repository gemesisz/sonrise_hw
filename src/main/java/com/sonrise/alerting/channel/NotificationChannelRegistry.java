package com.sonrise.alerting.channel;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Finds the {@link NotificationChannel} implementation for a {@code channel.code}.
 * Every channel bean is registered automatically.
 */
@Component
public class NotificationChannelRegistry {

    private final Map<String, NotificationChannel> channelsByCode;

    public NotificationChannelRegistry(List<NotificationChannel> channels) {
        // toMap throws IllegalStateException on duplicate keys: two beans claiming the same
        // code is a programming error and should stop the application from starting.
        this.channelsByCode = channels.stream()
                .collect(Collectors.toUnmodifiableMap(NotificationChannel::code, Function.identity()));
    }

    /**
     * @throws UnknownChannelException if no implementation exists for {@code code}
     */
    public NotificationChannel get(String code) {
        NotificationChannel channel = channelsByCode.get(code);
        if (channel == null) {
            throw new UnknownChannelException(code);
        }
        return channel;
    }
}
