package com.sonrise.alerting.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailChannelTest {

    @Mock
    private JavaMailSender mailSender;

    private EmailChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmailChannel(mailSender, "alerts@alerting.local");
    }

    @Test
    void codeMatchesSeededChannelRow() {
        assertThat(channel.code()).isEqualTo("EMAIL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice@example.com", "first.last+alerts@mail.example.co.uk"})
    void acceptsPlainEmailAddresses(String address) {
        assertThatCode(() -> channel.validateAddress(address)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "alice",
            "alice@",
            "@example.com",
            "alice smith@example.com",
            "Alice <alice@example.com>",
            "alice@example.com, bob@example.com"
    })
    void rejectsInvalidAddresses(String address) {
        assertThatThrownBy(() -> channel.validateAddress(address))
                .isInstanceOf(InvalidAddressException.class);
    }

    @Test
    void sendsPlainTextMailWithSeverityInSubject() {
        channel.send("alice@example.com", TestEvents.earthquake());

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();

        assertThat(message.getFrom()).isEqualTo("alerts@alerting.local");
        assertThat(message.getTo()).containsExactly("alice@example.com");
        assertThat(message.getSubject()).isEqualTo("[HIGH] M 6.1 - 10 km SW of Somewhere");
        assertThat(message.getText())
                .contains("Category: Natural disasters")
                .contains("Severity: HIGH")
                .contains("Occurred at: 2026-09-27T08:15:00Z")
                .contains("Shallow earthquake near the coast")
                .contains("https://earthquake.usgs.gov/earthquakes/eventpage/us7000abcd");
    }

    @Test
    void omitsMissingOptionalFields() {
        channel.send("alice@example.com", TestEvents.withoutOptionalFields());

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getText()).doesNotContain("null");
    }

    @Test
    void wrapsMailFailureInDeliveryException() {
        doThrow(new MailSendException("Connection refused")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> channel.send("alice@example.com", TestEvents.earthquake()))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("Connection refused")
                .hasCauseInstanceOf(MailSendException.class);
    }
}
