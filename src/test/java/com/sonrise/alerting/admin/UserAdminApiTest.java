package com.sonrise.alerting.admin;

import com.jayway.jsonpath.JsonPath;
import com.sonrise.alerting.domain.AppUser;
import com.sonrise.alerting.domain.Category;
import com.sonrise.alerting.domain.Event;
import com.sonrise.alerting.domain.Notification;
import com.sonrise.alerting.domain.Severity;
import com.sonrise.alerting.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserAdminApiTest extends AdminApiTestBase {

    @Autowired private CategoryRepository categories;

    @Test
    void createsUserWithLocationAndTrimmedName() throws Exception {
        mvc.perform(json(post("/api/admin/users"), """
                        {"name": "  Alice  "}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/admin/users/")))
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.subscriptions").isEmpty())
                .andExpect(jsonPath("$.channels").isEmpty());
    }

    @Test
    void rejectsBlankOrTooLongNameWithFieldErrors() throws Exception {
        mvc.perform(json(post("/api/admin/users"), """
                        {"name": "   "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errors.name").isNotEmpty());
        mvc.perform(json(post("/api/admin/users"), "{\"name\": \"" + "x".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").isNotEmpty());
    }

    @Test
    void unknownUserIsProblemDetail404() throws Exception {
        mvc.perform(asAdmin(get("/api/admin/users/999999")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("User 999999 not found"));
    }

    @Test
    void renamesUser() throws Exception {
        long id = createUser("Alice");

        mvc.perform(json(put("/api/admin/users/" + id), """
                        {"name": "Alice Smith"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice Smith"));
    }

    @Test
    void subscriptionIsCreatedThenUpdatedAndShownOnTheUser() throws Exception {
        long id = createUser("Alice");

        mvc.perform(json(put("/api/admin/users/" + id + "/subscriptions/MARKETS"), """
                        {"minSeverity": "LOW"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("MARKETS"))
                .andExpect(jsonPath("$.minSeverity").value("LOW"));
        mvc.perform(json(put("/api/admin/users/" + id + "/subscriptions/MARKETS"), """
                        {"minSeverity": "HIGH"}"""))
                .andExpect(status().isOk());

        mvc.perform(asAdmin(get("/api/admin/users/" + id)))
                .andExpect(jsonPath("$.subscriptions", hasSize(1)))
                .andExpect(jsonPath("$.subscriptions[0].minSeverity").value("HIGH"));
    }

    @Test
    void subscriptionValidation() throws Exception {
        long id = createUser("Alice");

        mvc.perform(json(put("/api/admin/users/" + id + "/subscriptions/SPORTS"), """
                        {"minSeverity": "LOW"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Category SPORTS not found"));
        mvc.perform(json(put("/api/admin/users/" + id + "/subscriptions/MARKETS"), """
                        {"minSeverity": "EXTREME"}"""))
                .andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/admin/users/" + id + "/subscriptions/MARKETS"), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.minSeverity").isNotEmpty());
    }

    @Test
    void unsubscribe() throws Exception {
        long id = createUser("Alice");
        mvc.perform(json(put("/api/admin/users/" + id + "/subscriptions/MARKETS"), """
                {"minSeverity": "LOW"}"""));

        mvc.perform(asAdmin(delete("/api/admin/users/" + id + "/subscriptions/MARKETS")))
                .andExpect(status().isNoContent());
        mvc.perform(asAdmin(delete("/api/admin/users/" + id + "/subscriptions/MARKETS")))
                .andExpect(status().isNotFound());
    }

    @Test
    void channelAddressIsValidatedByTheChannelItself() throws Exception {
        long id = createUser("Alice");

        mvc.perform(json(put("/api/admin/users/" + id + "/channels/EMAIL"), """
                        {"address": "Alice <alice@example.com>"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.address").value(containsString("Not a plain email address")));
        mvc.perform(json(put("/api/admin/users/" + id + "/channels/SLACK"), """
                        {"address": "https://evil.example.com/services/T/B/X"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.address").value(containsString("hooks.slack.com")));
        mvc.perform(json(put("/api/admin/users/" + id + "/channels/SMS"), """
                        {"address": "+36301234567"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void channelLinksAreSavedAndSlackWebhookIsMaskedInResponses() throws Exception {
        long id = createUser("Alice");
        String webhook = "https://hooks.slack.com/services/T0001/B0001/SeCrEtToKeN";

        mvc.perform(json(put("/api/admin/users/" + id + "/channels/EMAIL"), """
                        {"address": " alice@example.com "}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("alice@example.com"))
                .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(json(put("/api/admin/users/" + id + "/channels/SLACK"), "{\"address\": \"" + webhook + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address", endsWith("oKeN")))
                .andExpect(jsonPath("$.address", not(containsString("SeCrEt"))));
        mvc.perform(json(put("/api/admin/users/" + id + "/channels/EMAIL"), """
                        {"address": "alice@example.com", "enabled": false}"""))
                .andExpect(jsonPath("$.enabled").value(false));

        String user = mvc.perform(asAdmin(get("/api/admin/users/" + id)))
                .andExpect(jsonPath("$.channels", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        assertThat(user).doesNotContain("SeCrEt");
        assertThat(JsonPath.<Boolean>read(user, "$.channels[0].enabled")).isFalse(); // EMAIL, sorted by code
    }

    @Test
    void deletingUserDeletesSubscriptionsLinksAndNotificationHistory() throws Exception {
        long id = createUser("Alice");
        mvc.perform(json(put("/api/admin/users/" + id + "/subscriptions/MARKETS"), """
                {"minSeverity": "LOW"}"""));
        mvc.perform(json(put("/api/admin/users/" + id + "/channels/EMAIL"), """
                {"address": "alice@example.com"}"""));
        AppUser user = users.findById(id).orElseThrow();
        Category markets = categories.findByCode("MARKETS").orElseThrow();
        Event event = events.save(new Event(markets, "FAKE", "x", "t", null, null, Severity.LOW,
                Instant.now(), Instant.now()));
        notifications.save(new Notification(event, user, channels.findByCode("EMAIL").orElseThrow()));

        mvc.perform(asAdmin(delete("/api/admin/users/" + id)))
                .andExpect(status().isNoContent());

        assertThat(users.existsById(id)).isFalse();
        assertThat(notifications.count()).isZero();
        assertThat(events.count()).isEqualTo(1); // events are not the user's to delete
        mvc.perform(asAdmin(delete("/api/admin/users/" + id))).andExpect(status().isNotFound());
    }

    @Test
    void listsUsersWithTheirSubscriptionsAndChannels() throws Exception {
        long alice = createUser("Alice");
        createUser("Bob");
        mvc.perform(json(put("/api/admin/users/" + alice + "/subscriptions/NATURAL_DISASTERS"), """
                {"minSeverity": "CRITICAL"}"""));

        mvc.perform(asAdmin(get("/api/admin/users")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Alice"))
                .andExpect(jsonPath("$[0].subscriptions[0].category").value("NATURAL_DISASTERS"))
                .andExpect(jsonPath("$[1].subscriptions").isEmpty());
    }

    private long createUser(String name) throws Exception {
        String body = mvc.perform(json(post("/api/admin/users"), "{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(body, "$.id").longValue();
    }
}
