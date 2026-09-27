package com.sonrise.alerting.channel;

import com.sonrise.alerting.domain.Category;
import com.sonrise.alerting.domain.Event;
import com.sonrise.alerting.domain.Severity;

import java.time.Instant;

final class TestEvents {

    static final Instant OCCURRED_AT = Instant.parse("2026-09-27T08:15:00Z");

    private TestEvents() {
    }

    static Event earthquake() {
        Category category = new Category("NATURAL_DISASTERS", "Natural disasters", null);
        return new Event(category, "USGS", "us7000abcd", "M 6.1 - 10 km SW of Somewhere",
                "Shallow earthquake near the coast", "https://earthquake.usgs.gov/earthquakes/eventpage/us7000abcd",
                Severity.HIGH, OCCURRED_AT, OCCURRED_AT);
    }

    static Event withoutOptionalFields() {
        Category category = new Category("BREAKING_NEWS", "Breaking news", null);
        return new Event(category, "RSS", "item-1", "Headline", null, null,
                Severity.LOW, OCCURRED_AT, OCCURRED_AT);
    }
}
