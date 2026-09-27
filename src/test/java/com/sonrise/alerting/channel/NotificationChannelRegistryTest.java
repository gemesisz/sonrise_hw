package com.sonrise.alerting.channel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationChannelRegistryTest {

    @Test
    void findsChannelByCode() {
        NotificationChannel email = channel("EMAIL");
        NotificationChannel slack = channel("SLACK");
        NotificationChannelRegistry registry = new NotificationChannelRegistry(List.of(email, slack));

        assertThat(registry.get("EMAIL")).isSameAs(email);
        assertThat(registry.get("SLACK")).isSameAs(slack);
    }

    @Test
    void unknownCodeFailsWithClearMessage() {
        NotificationChannelRegistry registry = new NotificationChannelRegistry(List.of(channel("EMAIL")));

        assertThatThrownBy(() -> registry.get("SMS"))
                .isInstanceOf(UnknownChannelException.class)
                .hasMessageContaining("SMS");
    }

    @Test
    void codeLookupIsExact() {
        NotificationChannelRegistry registry = new NotificationChannelRegistry(List.of(channel("EMAIL")));

        assertThatThrownBy(() -> registry.get("email")).isInstanceOf(UnknownChannelException.class);
    }

    @Test
    void twoImplementationsWithSameCodeAreRejected() {
        List<NotificationChannel> duplicates = List.of(channel("EMAIL"), channel("EMAIL"));

        assertThatThrownBy(() -> new NotificationChannelRegistry(duplicates))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMAIL");
    }

    private static NotificationChannel channel(String code) {
        NotificationChannel channel = mock(NotificationChannel.class);
        when(channel.code()).thenReturn(code);
        return channel;
    }
}
