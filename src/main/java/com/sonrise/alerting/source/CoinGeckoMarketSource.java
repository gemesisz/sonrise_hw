package com.sonrise.alerting.source;

import com.sonrise.alerting.domain.Severity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Market movements from CoinGecko's public price API (no API key): an event when a coin's
 * 24h price change is large enough. Severity comes from the size of the change.
 *
 * <p>The API is stateless (current 24h change only), so dedup uses an id of
 * coin + UTC day + direction + severity: at most one event per coin, direction and level
 * per day, and a new one if the move escalates to a higher level.
 */
@Component
public class CoinGeckoMarketSource extends AbstractEventSource<String> {

    public static final String CODE = "COINGECKO";
    static final String CATEGORY = "MARKETS";

    /** Moves smaller than this (in %) are not events at all. */
    static final double MIN_CHANGE_PERCENT = 3.0;

    private final RestClient restClient;
    private final String url;
    private final List<String> coins;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public CoinGeckoMarketSource(@Value("${alerting.sources.coingecko.enabled}") boolean enabled,
                                 @Value("${alerting.sources.coingecko.interval}") Duration interval,
                                 @Value("${alerting.sources.coingecko.url}") String url,
                                 @Value("${alerting.sources.coingecko.coins}") List<String> coins,
                                 RestClient.Builder restClientBuilder,
                                 JsonMapper jsonMapper,
                                 Clock clock,
                                 EventStore eventStore) {
        super(CODE, enabled, interval, eventStore);
        this.restClient = restClientBuilder.build();
        this.url = url;
        this.coins = coins.stream().map(String::trim).filter(coin -> !coin.isEmpty()).toList();
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * @return the severity for an absolute 24h change in percent, or {@code null} below the threshold
     */
    static Severity severityFor(double absoluteChangePercent) {
        if (absoluteChangePercent >= 20.0) {
            return Severity.CRITICAL;
        }
        if (absoluteChangePercent >= 10.0) {
            return Severity.HIGH;
        }
        if (absoluteChangePercent >= 5.0) {
            return Severity.MEDIUM;
        }
        if (absoluteChangePercent >= MIN_CHANGE_PERCENT) {
            return Severity.LOW;
        }
        return null;
    }

    @Override
    protected String fetch() {
        String uri = UriComponentsBuilder.fromUriString(url)
                .queryParam("ids", String.join(",", coins))
                .queryParam("vs_currencies", "usd")
                .queryParam("include_24hr_change", "true")
                .build()
                .toUriString();
        return restClient.get().uri(uri).retrieve().body(String.class);
    }

    @Override
    protected List<EventCandidate> parse(String raw) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<EventCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, JsonNode> coin : jsonMapper.readTree(raw).properties()) {
            JsonNode price = coin.getValue().path("usd");
            JsonNode change = coin.getValue().path("usd_24h_change");
            if (!price.isNumber() || !change.isNumber()) {
                continue;
            }
            double changePercent = change.asDouble();
            Severity severity = severityFor(Math.abs(changePercent));
            if (severity == null) {
                continue;
            }
            String direction = changePercent >= 0 ? "UP" : "DOWN";
            String name = displayName(coin.getKey());
            candidates.add(new EventCandidate(
                    CATEGORY,
                    coin.getKey() + ":" + today + ":" + direction + ":" + severity,
                    String.format(Locale.ROOT, "%s %s %.1f%% in 24h", name,
                            changePercent >= 0 ? "up" : "down", Math.abs(changePercent)),
                    String.format(Locale.ROOT, "Price: %s USD, 24h change: %+.2f%%",
                            price.asString(), changePercent),
                    "https://www.coingecko.com/en/coins/" + coin.getKey(),
                    severity,
                    now));
        }
        return candidates;
    }

    private static String displayName(String coinId) {
        return coinId.isEmpty() ? coinId : Character.toUpperCase(coinId.charAt(0)) + coinId.substring(1);
    }
}
