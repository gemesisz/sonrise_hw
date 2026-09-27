package com.sonrise.alerting.source;

import com.sonrise.alerting.domain.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RssNewsSourceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final RssNewsSource source = new RssNewsSource(true, Duration.ofMinutes(10), "http://unused", Severity.MEDIUM,
            List.of("killed", " WAR ", "evacuat*", ""), RestClient.builder(), Clock.fixed(NOW, ZoneOffset.UTC),
            mock(EventStore.class));

    @Test
    void parsesItemsFromRealBbcSample() {
        List<EventCandidate> candidates = source.parse(Fixtures.read("bbc-world-sample.xml"));

        assertThat(candidates).hasSize(3);
        EventCandidate first = candidates.getFirst();
        assertThat(first.categoryCode()).isEqualTo("BREAKING_NEWS");
        assertThat(first.externalId()).isEqualTo("https://www.bbc.co.uk/news/articles/cmvgyyw2jeego#0");
        assertThat(first.title()).startsWith("Iran says it will wait for official US response");
        assertThat(first.url()).startsWith("https://www.bbc.co.uk/news/articles/cmvgyyw2jeego");
        assertThat(first.description()).isNotBlank();
        assertThat(first.occurredAt()).isEqualTo(Instant.parse("2026-09-27T02:16:52Z"));
    }

    @Test
    void keywordInTitleRaisesSeverityToHigh() {
        List<EventCandidate> candidates = source.parse(Fixtures.read("bbc-world-sample.xml"));

        // Of the three real headlines, only the helicopter crash contains a keyword ("killed").
        assertThat(candidates).extracting(EventCandidate::severity)
                .containsExactly(Severity.MEDIUM, Severity.MEDIUM, Severity.HIGH);
        assertThat(candidates.get(2).title()).isEqualTo("Four killed in helicopter crash near Montreal");
    }

    @ParameterizedTest
    @CsvSource({
            "Civil War escalates, HIGH",          // trimmed, case-insensitive keyword
            "'War: day 3', HIGH",                 // punctuation is a word boundary
            "Towns evacuated as fires spread, HIGH", // prefix keyword (evacuat*)
            "Warm weather ahead, MEDIUM",         // whole word: 'war' is not 'warm'
            "Film wins award, MEDIUM",
            "Storm warning issued, MEDIUM",
            "Local elections, MEDIUM"
    })
    void keywordsMatchWholeWordsCaseInsensitively(String title, Severity expected) {
        assertThat(source.severityFor(title)).isEqualTo(expected);
    }

    @Test
    void fallsBackToLinkWhenGuidMissingAndToNowWhenDateInvalid() {
        String xml = """
                <rss version="2.0"><channel>
                  <item><title>No guid</title><link>https://example.com/a</link><pubDate>not a date</pubDate></item>
                  <item><link>https://example.com/no-title</link></item>
                </channel></rss>""";

        assertThat(source.parse(xml)).singleElement().satisfies(c -> {
            assertThat(c.externalId()).isEqualTo("https://example.com/a");
            assertThat(c.occurredAt()).isEqualTo(NOW);
        });
    }

    @Test
    void rejectsDoctypeToPreventXxe() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE rss [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <rss version="2.0"><channel><item><title>&secret;</title><guid>1</guid></item></channel></rss>""";

        assertThatThrownBy(() -> source.parse(xxe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DOCTYPE");
    }
}
