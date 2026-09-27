package com.sonrise.alerting.notification;

import com.sonrise.alerting.channel.NotificationChannel;
import com.sonrise.alerting.channel.NotificationChannelRegistry;
import com.sonrise.alerting.channel.NotificationDeliveryException;
import com.sonrise.alerting.domain.AppUser;
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
import com.sonrise.alerting.source.EventCandidate;
import com.sonrise.alerting.source.FakeEventSource;
import com.sonrise.alerting.testsupport.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * Retry flow with real commits and a controllable clock: first delivery fails →
 * retry job re-sends when due → SENT, or FAILED_PERMANENTLY after max attempts
 * (config: 4 attempts, backoff 1m, 5m, 15m).
 */
@SpringBootTest
class NotificationRetryIntegrationTest {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.now());
        }
    }

    @MockitoBean
    private NotificationChannelRegistry channelRegistry;

    @Autowired private MutableClock clock;
    @Autowired private NotificationRetryJob retryJob;
    @Autowired private FakeEventSource fakeSource;
    @Autowired private AppUserRepository users;
    @Autowired private CategoryRepository categories;
    @Autowired private ChannelRepository channels;
    @Autowired private UserCategoryRepository subscriptions;
    @Autowired private UserChannelRepository userChannels;
    @Autowired private EventRepository events;
    @Autowired private NotificationRepository notifications;

    private final NotificationChannel email = mock(NotificationChannel.class);

    @BeforeEach
    void setUp() {
        clock.set(Instant.now());
        when(channelRegistry.get("EMAIL")).thenReturn(email);
    }

    @AfterEach
    void cleanUp() {
        notifications.deleteAll();
        events.deleteAll();
        users.deleteAll();
        reset(email);
    }

    @Test
    void failedDeliveryIsRetriedWhenDueAndThenSucceeds() {
        subscribe("alice@example.com");
        doThrow(new NotificationDeliveryException("SMTP down", null))
                .doNothing()
                .when(email).send(anyString(), any(Event.class));

        detect();
        Notification failed = single();
        assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(1)));

        clock.advance(Duration.ofSeconds(59));
        assertThat(retryJob.retryDue()).isZero();           // not due yet

        clock.advance(Duration.ofSeconds(1));
        assertThat(retryJob.retryDue()).isEqualTo(1);        // due exactly at next_attempt_at

        Notification sent = single();
        assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(sent.getAttempts()).isEqualTo(2);
        assertThat(sent.getLastError()).isNull();
        assertThat(sent.getNextAttemptAt()).isNull();
        verify(email, times(2)).send(eq("alice@example.com"), any(Event.class));
    }

    @Test
    void givesUpAfterMaxAttemptsWithGrowingBackoff() {
        subscribe("alice@example.com");
        doThrow(new NotificationDeliveryException("mailbox full", null))
                .when(email).send(anyString(), any(Event.class));

        detect();                                            // attempt 1
        clock.advance(Duration.ofMinutes(1));
        retryJob.retryDue();                                 // attempt 2
        assertThat(single().getNextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
        clock.advance(Duration.ofMinutes(5));
        retryJob.retryDue();                                 // attempt 3
        assertThat(single().getNextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        clock.advance(Duration.ofMinutes(15));
        retryJob.retryDue();                                 // attempt 4 = max

        Notification gaveUp = single();
        assertThat(gaveUp.getStatus()).isEqualTo(NotificationStatus.FAILED_PERMANENTLY);
        assertThat(gaveUp.getAttempts()).isEqualTo(4);
        assertThat(gaveUp.getNextAttemptAt()).isNull();
        assertThat(gaveUp.getLastError()).isEqualTo("mailbox full");

        clock.advance(Duration.ofDays(1));
        assertThat(retryJob.retryDue()).isZero();            // never picked up again
        verify(email, times(4)).send(anyString(), any(Event.class));
    }

    @Test
    void retryUsesTheCurrentAddress() {
        UserChannel link = subscribe("typo@exampel.com");
        doThrow(new NotificationDeliveryException("unknown domain", null))
                .doNothing()
                .when(email).send(anyString(), any(Event.class));
        detect();

        link.setAddress("alice@example.com");                // admin fixes the address
        userChannels.save(link);
        clock.advance(Duration.ofMinutes(1));
        retryJob.retryDue();

        verify(email).send(eq("alice@example.com"), any(Event.class));
        assertThat(single().getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void removedOrDisabledLinkEndsRetriesWithoutSending() {
        UserChannel link = subscribe("alice@example.com");
        doThrow(new NotificationDeliveryException("SMTP down", null))
                .when(email).send(anyString(), any(Event.class));
        detect();

        link.setEnabled(false);
        userChannels.save(link);
        clock.advance(Duration.ofMinutes(1));
        retryJob.retryDue();

        verify(email, times(1)).send(anyString(), any(Event.class)); // only the first attempt
        Notification abandoned = single();
        assertThat(abandoned.getStatus()).isEqualTo(NotificationStatus.FAILED_PERMANENTLY);
        assertThat(abandoned.getAttempts()).isEqualTo(1);
        assertThat(abandoned.getLastError()).contains("removed or disabled");
    }

    @Test
    void sentNotificationsAreNeverRetried() {
        subscribe("alice@example.com");
        doNothing().when(email).send(anyString(), any(Event.class));
        detect();

        clock.advance(Duration.ofDays(1));

        assertThat(retryJob.retryDue()).isZero();
        verify(email, times(1)).send(anyString(), any(Event.class));
    }

    private UserChannel subscribe(String address) {
        AppUser user = users.save(new AppUser("Alice"));
        subscriptions.save(new UserCategory(user, categories.findByCode("MARKETS").orElseThrow(), Severity.LOW));
        return userChannels.save(new UserChannel(user, channels.findByCode("EMAIL").orElseThrow(), address));
    }

    private void detect() {
        fakeSource.inject(new EventCandidate("MARKETS", "evt-" + System.nanoTime(), "Market crash", null, null,
                Severity.HIGH, clock.instant()));
        fakeSource.detect();
    }

    private Notification single() {
        List<Notification> all = notifications.findAll();
        assertThat(all).hasSize(1);
        return all.getFirst();
    }
}
