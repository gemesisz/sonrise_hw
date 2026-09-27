package com.sonrise.alerting.source;

import com.sonrise.alerting.domain.Severity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.time.Duration;
import java.io.IOException;
import java.io.StringReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Breaking news from an RSS 2.0 feed (no API key). RSS has no notion of importance, so every
 * item gets the configured default severity, raised to HIGH if its title contains one of the
 * configured keywords.
 */
@Component
public class RssNewsSource extends AbstractEventSource<String> {

    public static final String CODE = "RSS";
    static final String CATEGORY = "BREAKING_NEWS";

    private final RestClient restClient;
    private final String url;
    private final Severity defaultSeverity;
    private final List<Pattern> highSeverityKeywords;
    private final Clock clock;

    public RssNewsSource(@Value("${alerting.sources.rss.enabled}") boolean enabled,
                         @Value("${alerting.sources.rss.interval}") Duration interval,
                         @Value("${alerting.sources.rss.url}") String url,
                         @Value("${alerting.sources.rss.default-severity}") Severity defaultSeverity,
                         @Value("${alerting.sources.rss.high-severity-keywords}") List<String> highSeverityKeywords,
                         RestClient.Builder restClientBuilder,
                         Clock clock,
                         EventStore eventStore) {
        super(CODE, enabled, interval, eventStore);
        this.restClient = restClientBuilder.build();
        this.url = url;
        this.defaultSeverity = defaultSeverity;
        this.highSeverityKeywords = highSeverityKeywords.stream()
                .map(String::trim)
                .filter(keyword -> !keyword.isEmpty())
                .map(RssNewsSource::keywordPattern)
                .toList();
        this.clock = clock;
    }

    @Override
    protected String fetch() {
        return restClient.get().uri(url).retrieve().body(String.class);
    }

    @Override
    protected List<EventCandidate> parse(String raw) {
        NodeList items = parseXml(raw).getElementsByTagName("item");
        List<EventCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String title = text(item, "title");
            String link = text(item, "link");
            // guid is the item's stable id; fall back to the link when a feed omits it.
            String id = text(item, "guid") != null ? text(item, "guid") : link;
            if (title == null || id == null) {
                continue;
            }
            candidates.add(new EventCandidate(
                    CATEGORY, id, title, text(item, "description"), link,
                    severityFor(title), publishedAt(text(item, "pubDate"))));
        }
        return candidates;
    }

    Severity severityFor(String title) {
        return highSeverityKeywords.stream().anyMatch(keyword -> keyword.matcher(title).find())
                ? Severity.HIGH
                : defaultSeverity;
    }

    /**
     * Whole-word, case-insensitive match ("war" must not match "warm" or "award");
     * a trailing {@code *} makes it a prefix match ("evacuat*" matches "evacuation").
     */
    private static Pattern keywordPattern(String keyword) {
        String regex = keyword.endsWith("*")
                ? "\\b" + Pattern.quote(keyword.substring(0, keyword.length() - 1))
                : "\\b" + Pattern.quote(keyword) + "\\b";
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private Instant publishedAt(String pubDate) {
        if (pubDate == null) {
            return clock.instant();
        }
        try {
            return ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            // Feeds get dates wrong surprisingly often; the item is still worth reporting.
            return clock.instant();
        }
    }

    private static Document parseXml(String raw) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // The feed is untrusted input: block DOCTYPEs entirely (prevents XXE and entity expansion).
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new InputSource(new StringReader(raw)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("Invalid RSS XML: " + e.getMessage(), e);
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent().trim();
        return value.isEmpty() ? null : value;
    }
}
