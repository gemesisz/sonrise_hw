package com.sonrise.alerting.channel;

import com.sonrise.alerting.domain.Event;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

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
        } catch (RestClientResponseException e) {
            // Slack answered with an error status; its body names the problem (e.g. "no_service").
            throw new NotificationDeliveryException(
                    "Slack webhook returned " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            // I/O errors (timeouts, refused connections) put the full URL in their message, and the
            // webhook URL is a secret: anyone holding it can post. Mask it before it reaches the
            // database or the admin view.
            throw new NotificationDeliveryException(
                    "Slack webhook call failed: " + redact(e.getMessage(), address), e);
        }
    }

    /**
     * The webhook URL is a secret (anyone holding it can post): show only enough to recognise it.
     */
    @Override
    public String displayAddress(String address) {
        if (address == null || address.length() <= 8) {
            return "<webhook URL>";
        }
        return "https://" + WEBHOOK_HOST + WEBHOOK_PATH_PREFIX + "…" + address.substring(address.length() - 4);
    }

    private static String redact(String message, String webhookUrl) {
        return message == null ? null : message.replace(webhookUrl, "<webhook URL>");
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
