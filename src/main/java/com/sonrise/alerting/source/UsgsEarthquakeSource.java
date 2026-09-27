package com.sonrise.alerting.source;

import com.sonrise.alerting.domain.Severity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Earthquakes from the USGS GeoJSON summary feed (no API key). Severity comes from magnitude.
 * Format: https://earthquake.usgs.gov/earthquakes/feed/v1.0/geojson.php
 */
@Component
public class UsgsEarthquakeSource extends AbstractEventSource<String> {

    public static final String CODE = "USGS";
    static final String CATEGORY = "NATURAL_DISASTERS";

    private final RestClient restClient;
    private final String url;
    private final JsonMapper jsonMapper;

    public UsgsEarthquakeSource(@Value("${alerting.sources.usgs.enabled}") boolean enabled,
                                @Value("${alerting.sources.usgs.url}") String url,
                                RestClient.Builder restClientBuilder,
                                JsonMapper jsonMapper,
                                EventStore eventStore) {
        super(CODE, enabled, eventStore);
        this.restClient = restClientBuilder.build();
        this.url = url;
        this.jsonMapper = jsonMapper;
    }

    static Severity severityFor(double magnitude) {
        if (magnitude >= 7.0) {
            return Severity.CRITICAL;
        }
        if (magnitude >= 6.0) {
            return Severity.HIGH;
        }
        if (magnitude >= 5.0) {
            return Severity.MEDIUM;
        }
        return Severity.LOW;
    }

    @Override
    protected String fetch() {
        return restClient.get().uri(url).retrieve().body(String.class);
    }

    @Override
    protected List<EventCandidate> parse(String raw) {
        List<EventCandidate> candidates = new ArrayList<>();
        for (JsonNode feature : jsonMapper.readTree(raw).path("features")) {
            JsonNode properties = feature.path("properties");
            // The feed also lists quarry blasts, explosions, ice quakes: not natural disasters.
            if (!"earthquake".equals(properties.path("type").asString(null))) {
                continue;
            }
            String id = feature.path("id").asString(null);
            String title = properties.path("title").asString(null);
            JsonNode magnitude = properties.path("mag");
            JsonNode time = properties.path("time");
            if (id == null || title == null || !magnitude.isNumber() || !time.isNumber()) {
                continue;
            }
            candidates.add(new EventCandidate(
                    CATEGORY,
                    id,
                    title,
                    properties.path("tsunami").asInt(0) == 1 ? "Tsunami alert issued" : null,
                    properties.path("url").asString(null),
                    severityFor(magnitude.asDouble()),
                    Instant.ofEpochMilli(time.asLong())));
        }
        return candidates;
    }
}
