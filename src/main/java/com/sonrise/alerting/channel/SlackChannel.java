package com.sonrise.alerting.channel;

import com.sonrise.alerting.domain.Event;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

/**
 * Posts events to a Slack incoming webhook. The address is the webhook URL
 * ({@code https://hooks.slack.com/services/...}).
 */
@Component
public class SlackChannel implements NotificationChannel {

    public static final String CODE = "SLACK";

    private static final String WEBHOOK_HOST = "hooks.slack.com";
    private static final String WEBHOOK_PATH_PREFIX = "/services/";

    private final RestClient restClient;

    public SlackChannel(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public void validateAddress(String address) {
        if (address == null || address.isBlank()) {
            throw new InvalidAddressException("Slack webhook URL is required");
        }
        URI uri;
        try {
            uri = new URI(address);
        } catch (URISyntaxException e) {
            throw new InvalidAddressException("Invalid Slack webhook URL: " + address);
        }
        if (!"https".equals(uri.getScheme())
                || !WEBHOOK_HOST.equals(uri.getHost())
                || uri.getPath() == null
                || !uri.getPath().startsWith(WEBHOOK_PATH_PREFIX)) {
            throw new InvalidAddressException(
                    "Slack webhook URL must look like https://" + WEBHOOK_HOST + WEBHOOK_PATH_PREFIX + "...");
        }
    }

    @Override
    public void send(String address, Event event) {
        try {
            restClient.post()
                    .uri(address)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", text(event)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            // Deliberately not including the webhook URL: it is a secret.
            throw new NotificationDeliveryException("Slack webhook call failed: " + e.getMessage(), e);
        }
    }

    private String text(Event event) {
        StringBuilder text = new StringBuilder()
                .append("*[").append(event.getSeverity()).append("] ").append(event.getTitle()).append("*\n")
                .append(event.getCategory().getName()).append(" · ").append(event.getOccurredAt());
        if (event.getDescription() != null) {
            text.append('\n').append(event.getDescription());
        }
        if (event.getUrl() != null) {
            text.append('\n').append(event.getUrl());
        }
        return text.toString();
    }
}
