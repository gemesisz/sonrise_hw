package com.sonrise.alerting.source;

import com.sonrise.alerting.domain.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CoinGeckoMarketSourceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CoinGeckoMarketSource source = new CoinGeckoMarketSource(true,
            "https://api.coingecko.com/api/v3/simple/price", List.of("bitcoin", " ethereum "),
            builder, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC), mock(EventStore.class));

    @Test
    void requestsConfiguredCoinsWith24hChange() {
        server.expect(requestTo("https://api.coingecko.com/api/v3/simple/price"
                        + "?ids=bitcoin,ethereum&vs_currencies=usd&include_24hr_change=true"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        source.fetch();

        server.verify();
    }

    @Test
    void realSampleWithSmallMovesProducesNoEvents() {
        // Captured live: all 24h changes were below 1%.
        assertThat(source.parse(Fixtures.read("coingecko-simple-price-sample.json"))).isEmpty();
    }

    @Test
    void largeMoveBecomesEventWithDirectionAndDailyId() {
        String json = """
                {"bitcoin": {"usd": 76000, "usd_24h_change": -12.34},
                 "ethereum": {"usd": 2700.5, "usd_24h_change": 1.2}}""";

        assertThat(source.parse(json)).singleElement().satisfies(c -> {
            assertThat(c.categoryCode()).isEqualTo("MARKETS");
            assertThat(c.externalId()).isEqualTo("bitcoin:2026-09-27:DOWN:HIGH");
            assertThat(c.title()).isEqualTo("Bitcoin down 12.3% in 24h");
            assertThat(c.description()).isEqualTo("Price: 76000 USD, 24h change: -12.34%");
            assertThat(c.severity()).isEqualTo(Severity.HIGH);
            assertThat(c.occurredAt()).isEqualTo(NOW);
        });
    }

    @Test
    void sameMoveLaterTheSameDayHasSameIdSoItIsDeduplicated() {
        String json = """
                {"bitcoin": {"usd": 76000, "usd_24h_change": 6.0}}""";
        String later = """
                {"bitcoin": {"usd": 77000, "usd_24h_change": 7.5}}""";

        assertThat(source.parse(json).getFirst().externalId())
                .isEqualTo(source.parse(later).getFirst().externalId());
    }

    @Test
    void skipsCoinsWithMissingData() {
        String json = """
                {"bitcoin": {"usd": 76000}, "ethereum": {"usd_24h_change": 25.0}}""";

        assertThat(source.parse(json)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "2.99, ", "3.0, LOW", "4.99, LOW",
            "5.0, MEDIUM", "9.99, MEDIUM",
            "10.0, HIGH", "19.99, HIGH",
            "20.0, CRITICAL", "55.0, CRITICAL"
    })
    void severityFromAbsoluteChange(double change, Severity expected) {
        assertThat(CoinGeckoMarketSource.severityFor(change)).isEqualTo(expected);
    }
}
