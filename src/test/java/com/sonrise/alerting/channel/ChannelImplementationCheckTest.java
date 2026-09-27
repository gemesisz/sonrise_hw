package com.sonrise.alerting.channel;

import com.sonrise.alerting.domain.Channel;
import com.sonrise.alerting.repository.ChannelRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelImplementationCheckTest {

    private final ChannelRepository repository = mock(ChannelRepository.class);

    @Test
    void passesWhenRowsAndImplementationsMatch() {
        when(repository.findAll()).thenReturn(List.of(new Channel("EMAIL", "Email"), new Channel("SLACK", "Slack")));

        assertThatCode(() -> check("EMAIL", "SLACK").run(null)).doesNotThrowAnyException();
    }

    @Test
    void failsForAChannelRowWithoutImplementation() {
        when(repository.findAll()).thenReturn(List.of(new Channel("EMAIL", "Email"), new Channel("SMS", "SMS")));

        assertThatThrownBy(() -> check("EMAIL").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without an implementation [SMS]");
    }

    @Test
    void failsForAnImplementationWithoutChannelRow() {
        when(repository.findAll()).thenReturn(List.of(new Channel("EMAIL", "Email")));

        assertThatThrownBy(() -> check("EMAIL", "TEAMS").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without a channel row [TEAMS]");
    }

    private ChannelImplementationCheck check(String... codes) {
        List<NotificationChannel> implementations = java.util.Arrays.stream(codes).map(code -> {
            NotificationChannel channel = mock(NotificationChannel.class);
            when(channel.code()).thenReturn(code);
            return channel;
        }).toList();
        return new ChannelImplementationCheck(repository, implementations);
    }
}
