package com.sonrise.alerting.notification;

import com.sonrise.alerting.channel.NotificationChannel;
import com.sonrise.alerting.channel.NotificationChannelRegistry;
import com.sonrise.alerting.channel.NotificationDeliveryException;
import com.sonrise.alerting.domain.AppUser;
import com.sonrise.alerting.domain.Channel;
import com.sonrise.alerting.domain.Event;
import com.sonrise.alerting.domain.Notification;
import com.sonrise.alerting.domain.NotificationStatus;
import com.sonrise.alerting.domain.Severity;
import com.sonrise.alerting.domain.UserCategory;
import com.sonrise.alerting.domain.UserChannel;
import com.sonrise.alerting.repository.AppUserRepository;
import com.sonrise.alerting.repository.CategoryRepository;
import com.sonrise.alerting.repository.ChannelRepository;
import com.sonrise.alerting.repository.EventRepository;
import com.sonrise.alerting.repository.NotificationRepository;
import com.sonrise.alerting.repository.UserCategoryRepository;
import com.sonrise.alerting.repository.UserChannelRepository;
import com.sonrise.alerting.source.DetectionResult;
import com.sonrise.alerting.source.EventCandidate;
import com.sonrise.alerting.source.FakeEventSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs the real Observer flow with real commits: fake source → EventStore → commit →
 * EventDetected → dispatcher. Only the channel implementations are mocked.
 * Not @Transactional on purpose: AFTER_COMMIT listeners only fire on a real commit.
 */
@SpringBootTest
class NotificationDispatcherIntegrationTest {

    @MockitoBean
    private NotificationChannelRegistry channelRegistry;

    @MockitoSpyBean
    private UserChannelRepository userChannelsSpy;

    @Autowired private FakeEventSource fakeSource;
    @Autowired private AppUserRepository users;
    @Autowired private CategoryRepository categories;
    @Autowired private ChannelRepository channels;
    @Autowired private UserCategoryRepository subscriptions;
    @Autowired private EventRepository events;
    @Autowired private NotificationRepository notifications;
    @Autowired private PlatformTransactionManager transactionManager;

    private final NotificationChannel email = mock(NotificationChannel.class);
    private final NotificationChannel slack = mock(NotificationChannel.class);

    @BeforeEach
    void setUp() {
        when(channelRegistry.get("EMAIL")).thenReturn(email);
        when(channelRegistry.get("SLACK")).thenReturn(slack);
    }

    @AfterEach
    void cleanUp() {
        notifications.deleteAll();
        events.deleteAll();
        users.deleteAll(); // cascades subscriptions and channel links
        channels.findAll().forEach(channel -> {
            channel.setEnabled(true);
            channels.save(channel);
        });
    }

    @Test
    void notifiesSubscriberOnEveryEnabledChannel() {
        AppUser alice = user("Alice", "MARKETS", Severity.MEDIUM);
        link(alice, "EMAIL", "alice@example.com");
        link(alice, "SLACK", "https://hooks.slack.com/services/T/B/alice");

        detect("MARKETS", Severity.HIGH);

        verify(email).send(eq("alice@example.com"), any(Event.class));
        verify(slack).send(eq("https://hooks.slack.com/services/T/B/alice"), any(Event.class));
        assertThat(notificationsByChannel().values())
                .hasSize(2)
                .allSatisfy(n -> {
                    assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
                    assertThat(n.getAttempts()).isEqualTo(1);
                    assertThat(n.getSentAt()).isNotNull();
                });
    }

    @Test
    void channelsCanReadEventCategoryOutsideTheTransaction() {
        // Guards D26: the event handed to channels must have its lazy category loaded.
        AppUser alice = user("Alice", "MARKETS", Severity.LOW);
        link(alice, "EMAIL", "alice@example.com");
        doAnswer(invocation -> {
            Event event = invocation.getArgument(1);
            assertThat(event.getCategory().getName()).isEqualTo("Market movements");
            return null;
        }).when(email).send(anyString(), any(Event.class));

        detect("MARKETS", Severity.LOW);

        assertThat(single().getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void onlyNotifiesSubscribersWhoseMinimumSeverityIsMet() {
        AppUser low = user("Low", "MARKETS", Severity.LOW);
        AppUser high = user("High", "MARKETS", Severity.HIGH);
        AppUser critical = user("Critical", "MARKETS", Severity.CRITICAL);
        link(low, "EMAIL", "low@example.com");
        link(high, "EMAIL", "high@example.com");
        link(critical, "EMAIL", "critical@example.com");

        detect("MARKETS", Severity.HIGH);

        verify(email).send(eq("low@example.com"), any(Event.class));
        verify(email).send(eq("high@example.com"), any(Event.class)); // equal severity counts
        verify(email, never()).send(eq("critical@example.com"), any(Event.class));
    }

    @Test
    void ignoresOtherCategoriesAndDisabledLinksAndChannels() {
        AppUser otherCategory = user("News reader", "BREAKING_NEWS", Severity.LOW);
        link(otherCategory, "EMAIL", "news@example.com");

        AppUser alice = user("Alice", "MARKETS", Severity.LOW);
        UserChannel disabledEmail = link(alice, "EMAIL", "alice@example.com");
        disabledEmail.setEnabled(false);
        userChannelsSpy.save(disabledEmail);
        link(alice, "SLACK", "https://hooks.slack.com/services/T/B/alice");
        Channel slackChannel = channels.findByCode("SLACK").orElseThrow();
        slackChannel.setEnabled(false);
        channels.save(slackChannel);

        detect("MARKETS", Severity.CRITICAL);

        verify(email, never()).send(anyString(), any(Event.class));
        verify(slack, never()).send(anyString(), any(Event.class));
        assertThat(notifications.count()).isZero();
    }

    @Test
    void oneFailingChannelDoesNotStopTheOthers() {
        AppUser alice = user("Alice", "MARKETS", Severity.LOW);
        link(alice, "EMAIL", "alice@example.com");
        link(alice, "SLACK", "https://hooks.slack.com/services/T/B/alice");
        doThrow(new NotificationDeliveryException("Email to alice@example.com failed: Connection refused", null))
                .when(email).send(anyString(), any(Event.class));

        detect("MARKETS", Severity.HIGH);

        verify(slack).send(anyString(), any(Event.class));
        Map<String, Notification> byChannel = notificationsByChannel();
        assertThat(byChannel.get("EMAIL").getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(byChannel.get("EMAIL").getAttempts()).isEqualTo(1);
        assertThat(byChannel.get("EMAIL").getLastError()).contains("Connection refused");
        assertThat(byChannel.get("SLACK").getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void unknownChannelImplementationIsRecordedAsFailedDelivery() {
        AppUser alice = user("Alice", "MARKETS", Severity.LOW);
        link(alice, "EMAIL", "alice@example.com");
        when(channelRegistry.get("EMAIL")).thenThrow(new IllegalStateException("boom"));

        detect("MARKETS", Severity.HIGH);

        assertThat(single().getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(single().getLastError()).isEqualTo("boom");
    }

    @Test
    void crashWhileDispatchingDoesNotBreakDetection() {
        user("Alice", "MARKETS", Severity.LOW);
        doThrow(new IllegalStateException("database hiccup"))
                .when(userChannelsSpy).findDeliveryTargets(any(), any());

        DetectionResult result = detect("MARKETS", Severity.HIGH, "evt-1", "evt-2");

        // Both events were stored and counted, although dispatching each of them crashed.
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.rejected()).isZero();
        assertThat(events.count()).isEqualTo(2);
    }

    @Test
    void duplicateEventIsNotNotifiedAgain() {
        AppUser alice = user("Alice", "MARKETS", Severity.LOW);
        link(alice, "EMAIL", "alice@example.com");

        detect("MARKETS", Severity.HIGH, "same-id");
        DetectionResult second = detect("MARKETS", Severity.HIGH, "same-id");

        assertThat(second.duplicates()).isEqualTo(1);
        verify(email, times(1)).send(anyString(), any(Event.class));
        assertThat(notifications.count()).isEqualTo(1);
    }

    @Test
    void storesButDoesNotNotifyEventsOlderThanMaxAge() {
        AppUser alice = user("Alice", "NATURAL_DISASTERS", Severity.LOW);
        link(alice, "EMAIL", "alice@example.com");

        // Like the first run after a restart: the feed still lists yesterday's earthquakes.
        DetectionResult result = detectAt("NATURAL_DISASTERS", Instant.now().minus(Duration.ofMinutes(11)), "old-quake");

        assertThat(result.created()).isEqualTo(1);
        assertThat(events.count()).isEqualTo(1);
        verify(email, never()).send(anyString(), any(Event.class));
        assertThat(notifications.count()).isZero();
    }

    @Test
    void notifiesEventsYoungerThanMaxAge() {
        AppUser alice = user("Alice", "NATURAL_DISASTERS", Severity.LOW);
        link(alice, "EMAIL", "alice@example.com");

        detectAt("NATURAL_DISASTERS", Instant.now().minus(Duration.ofMinutes(9)), "recent-quake");

        verify(email).send(eq("alice@example.com"), any(Event.class));
    }

    private DetectionResult detectAt(String category, Instant occurredAt, String id) {
        fakeSource.inject(new EventCandidate(category, id, "Test event " + id, null, null, Severity.HIGH, occurredAt));
        return fakeSource.detect();
    }

    private DetectionResult detect(String category, Severity severity, String... ids) {
        String[] externalIds = ids.length == 0 ? new String[]{"evt-" + System.nanoTime()} : ids;
        for (String id : externalIds) {
            fakeSource.inject(new EventCandidate(category, id, "Test event " + id, null, null, severity, Instant.now()));
        }
        return fakeSource.detect();
    }

    private AppUser user(String name, String categoryCode, Severity minSeverity) {
        AppUser user = users.save(new AppUser(name));
        subscriptions.save(new UserCategory(user, categories.findByCode(categoryCode).orElseThrow(), minSeverity));
        return user;
    }

    private UserChannel link(AppUser user, String channelCode, String address) {
        return userChannelsSpy.save(new UserChannel(user, channels.findByCode(channelCode).orElseThrow(), address));
    }

    private Notification single() {
        List<Notification> all = notifications.findAll();
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    private Map<String, Notification> notificationsByChannel() {
        // Channel is lazy on Notification: read the codes inside a transaction.
        return new TransactionTemplate(transactionManager).execute(status -> notifications.findAll().stream()
                .peek(n -> n.getChannel().getCode())
                .collect(Collectors.toMap(n -> n.getChannel().getCode(), Function.identity())));
    }
}
