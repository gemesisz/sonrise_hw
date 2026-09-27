package com.sonrise.alerting.source;

import com.sonrise.alerting.domain.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UsgsEarthquakeSourceTest {

    private final UsgsEarthquakeSource source = new UsgsEarthquakeSource(true, "http://unused",
            RestClient.builder(), JsonMapper.builder().build(), mock(EventStore.class));

    @Test
    void parsesEarthquakesFromRealFeedSample() {
        List<EventCandidate> candidates = source.parse(Fixtures.read("usgs-sample.geojson"));

        assertThat(candidates).extracting(EventCandidate::externalId)
                .containsExactly("us6000txtc", "us6000txtz", "us6000txt0");
        EventCandidate first = candidates.getFirst();
        assertThat(first.categoryCode()).isEqualTo("NATURAL_DISASTERS");
        assertThat(first.title()).isEqualTo("M 5.6 - 74 km ESE of Kokopo, Papua New Guinea");
        assertThat(first.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(first.occurredAt()).isEqualTo(Instant.ofEpochMilli(1790431697704L));
        assertThat(first.url()).isEqualTo("https://earthquake.usgs.gov/earthquakes/eventpage/us6000txtc");
        assertThat(first.description()).isNull();
    }

    @Test
    void ignoresNonEarthquakesSuchAsQuarryBlasts() {
        // The real sample contains a quarry blast (ok2026svfd).
        assertThat(source.parse(Fixtures.read("usgs-sample.geojson")))
                .extracting(EventCandidate::externalId)
                .doesNotContain("ok2026svfd");
    }

    @Test
    void skipsFeaturesWithoutMagnitudeAndFlagsTsunami() {
        String json = """
                {"features": [
                  {"id": "a", "properties": {"type": "earthquake", "mag": null, "time": 1, "title": "no mag"}},
                  {"id": "b", "properties": {"type": "earthquake", "mag": 7.4, "time": 1, "title": "big", "tsunami": 1}}
                ]}""";

        List<EventCandidate> candidates = source.parse(json);

        assertThat(candidates).singleElement().satisfies(c -> {
            assertThat(c.externalId()).isEqualTo("b");
            assertThat(c.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(c.description()).isEqualTo("Tsunami alert issued");
        });
    }

    @ParameterizedTest
    @CsvSource({
            "2.5, LOW", "4.99, LOW",
            "5.0, MEDIUM", "5.99, MEDIUM",
            "6.0, HIGH", "6.99, HIGH",
            "7.0, CRITICAL", "9.1, CRITICAL"
    })
    void severityFromMagnitude(double magnitude, Severity expected) {
        assertThat(UsgsEarthquakeSource.severityFor(magnitude)).isEqualTo(expected);
    }
}
