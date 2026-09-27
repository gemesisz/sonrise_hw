package com.sonrise.alerting.admin;

import com.sonrise.alerting.domain.AppUser;
import com.sonrise.alerting.domain.Event;
import com.sonrise.alerting.domain.Notification;
import com.sonrise.alerting.domain.Severity;
import com.sonrise.alerting.domain.UserCategory;
import com.sonrise.alerting.domain.UserChannel;
import com.sonrise.alerting.repository.CategoryRepository;
import com.sonrise.alerting.repository.UserCategoryRepository;
import com.sonrise.alerting.repository.UserChannelRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Detection trigger, fake events, event and notification lists, retry now, reference data.
 */
class OperationsAdminApiTest extends AdminApiTestBase {

    @Autowired private CategoryRepository categories;
    @Autowired private UserCategoryRepository subscriptions;
    @Autowired private UserChannelRepository userChannels;

    @Test
    void listsCategoriesChannelsAndSources() throws Exception {
        mvc.perform(asAdmin(get("/api/admin/categories")))
                .andExpect(jsonPath("$[*].code", containsInAnyOrder("BREAKING_NEWS", "MARKETS", "NATURAL_DISASTERS")));
        mvc.perform(asAdmin(get("/api/admin/channels")))
                .andExpect(jsonPath("$[*].code", containsInAnyOrder("EMAIL", "SLACK")));
        mvc.perform(asAdmin(get("/api/admin/sources")))
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[?(@.code == 'FAKE')].enabled").value(true))
                .andExpect(jsonPath("$[?(@.code == 'USGS')].enabled").value(false))
                .andExpect(jsonPath("$[?(@.code == 'USGS')].interval").value("PT5M"));
    }

    @Test
    void disablingAChannel() throws Exception {
        mvc.perform(json(patch("/api/admin/channels/SLACK"), """
                        {"enabled": false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(json(patch("/api/admin/channels/SLACK"), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.enabled").isNotEmpty());
    }

    @Test
    void runNowRunsOnlyEnabledSources() throws Exception {
        mvc.perform(asAdmin(post("/api/admin/detection/run")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].source").value("FAKE"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));
    }

    @Test
    void fakeEventGoesThroughThePipelineAndIsListed() throws Exception {
        subscribe("alice@example.com", Severity.LOW);
        doNothing().when(emailChannel).send(anyString(), any(Event.class));

        mvc.perform(json(post("/api/admin/fake-events"), """
                        {"category": "MARKETS", "title": "Demo: Bitcoin down 25%", "severity": "CRITICAL"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.created").value(1));

        verify(emailChannel).send(org.mockito.ArgumentMatchers.eq("alice@example.com"), any(Event.class));
        mvc.perform(asAdmin(get("/api/admin/events").param("category", "MARKETS").param("minSeverity", "HIGH")))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Demo: Bitcoin down 25%"))
                .andExpect(jsonPath("$.items[0].source").value("FAKE"));
        mvc.perform(asAdmin(get("/api/admin/notifications").param("status", "SENT")))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].userName").value("Alice"))
                .andExpect(jsonPath("$.items[0].channel").value("EMAIL"));
    }

    @Test
    void fakeEventValidation() throws Exception {
        mvc.perform(json(post("/api/admin/fake-events"), """
                        {"category": "SPORTS", "title": "x", "severity": "LOW"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown category SPORTS"));
        mvc.perform(json(post("/api/admin/fake-events"), """
                        {"category": "MARKETS"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").isNotEmpty())
                .andExpect(jsonPath("$.errors.severity").isNotEmpty());
    }

    @Test
    void eventFiltersAndPaging() throws Exception {
        for (int i = 0; i < 3; i++) {
            event("e" + i, Severity.LOW);
        }
        event("big", Severity.CRITICAL);

        mvc.perform(asAdmin(get("/api/admin/events").param("size", "2").param("page", "1")))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalItems").value(4))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(asAdmin(get("/api/admin/events").param("minSeverity", "HIGH")))
                .andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(asAdmin(get("/api/admin/events").param("source", "USGS")))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(asAdmin(get("/api/admin/events").param("size", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.size").isNotEmpty());
        mvc.perform(asAdmin(get("/api/admin/events").param("minSeverity", "EXTREME")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retryNowSendsAFailedNotification() throws Exception {
        Notification failed = notification(n -> n.markFailed("SMTP down", Instant.now().plusSeconds(3600)));
        doNothing().when(emailChannel).send(anyString(), any(Event.class));

        mvc.perform(asAdmin(post("/api/admin/notifications/" + failed.getId() + "/retry")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.attempts").value(2));
    }

    @Test
    void retryNowOnlyForFailed() throws Exception {
        Notification sent = notification(n -> n.markSent(Instant.now()));
        Notification permanent = notification(n -> n.markFailed("gave up", null));

        mvc.perform(asAdmin(post("/api/admin/notifications/" + sent.getId() + "/retry")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.endsWith("is SENT")));
        mvc.perform(asAdmin(post("/api/admin/notifications/" + permanent.getId() + "/retry")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.endsWith("is FAILED_PERMANENTLY")));
        mvc.perform(asAdmin(post("/api/admin/notifications/999999/retry")))
                .andExpect(status().isNotFound());
    }

    private void subscribe(String address, Severity minSeverity) {
        AppUser alice = users.save(new AppUser("Alice"));
        subscriptions.save(new UserCategory(alice, categories.findByCode("MARKETS").orElseThrow(), minSeverity));
        userChannels.save(new UserChannel(alice, channels.findByCode("EMAIL").orElseThrow(), address));
    }

    private Event event(String externalId, Severity severity) {
        return events.save(new Event(categories.findByCode("MARKETS").orElseThrow(), "FAKE", externalId, "t",
                null, null, severity, Instant.now(), Instant.now()));
    }

    private Notification notification(java.util.function.Consumer<Notification> state) {
        subscribe("alice@example.com", Severity.LOW);
        AppUser alice = users.findAll().getFirst();
        Notification notification = new Notification(event("n-" + System.nanoTime(), Severity.HIGH), alice,
                channels.findByCode("EMAIL").orElseThrow());
        state.accept(notification);
        return notifications.save(notification);
    }
}
