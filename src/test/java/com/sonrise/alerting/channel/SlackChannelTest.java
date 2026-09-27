package com.sonrise.alerting.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SlackChannelTest {

    private static final String WEBHOOK = "https://hooks.slack.com/services/T000/B000/XXXXSECRET";

    private MockRestServiceServer server;
    private SlackChannel channel;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        channel = new SlackChannel(builder);
    }

    @Test
    void codeMatchesSeededChannelRow() {
        assertThat(channel.code()).isEqualTo("SLACK");
    }

    @Test
    void acceptsSlackIncomingWebhookUrl() {
        assertThatCode(() -> channel.validateAddress(WEBHOOK)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "not a url",
            "http://hooks.slack.com/services/T000/B000/XXX",       // not https
            "https://evil.example.com/services/T000/B000/XXX",     // wrong host
            "https://hooks.slack.com.evil.example.com/services/X", // host suffix trick
            "https://hooks.slack.com/api/chat.postMessage",        // not a webhook path
            "alice@example.com"
    })
    void rejectsAnythingButSlackWebhookUrls(String address) {
        assertThatThrownBy(() -> channel.validateAddress(address))
                .isInstanceOf(InvalidAddressException.class);
    }

    @Test
    void postsJsonTextMessageToWebhook() {
        server.expect(requestTo(WEBHOOK))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.text", containsString("*[HIGH] M 6.1 - 10 km SW of Somewhere*")))
                .andExpect(jsonPath("$.text", containsString("Natural disasters")))
                .andExpect(jsonPath("$.text", containsString("https://earthquake.usgs.gov/")))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        channel.send(WEBHOOK, TestEvents.earthquake());

        server.verify();
    }

    @Test
    void wrapsSlackErrorWithoutLeakingWebhookUrl() {
        server.expect(requestTo(WEBHOOK))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("no_service"));

        assertThatThrownBy(() -> channel.send(WEBHOOK, TestEvents.earthquake()))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("404")
                .hasMessageNotContaining("XXXXSECRET");
    }
}
