package com.sonrise.alerting.domain;

import com.sonrise.alerting.repository.AppUserRepository;
import com.sonrise.alerting.repository.CategoryRepository;
import com.sonrise.alerting.repository.ChannelRepository;
import com.sonrise.alerting.repository.EventRepository;
import com.sonrise.alerting.repository.NotificationRepository;
import com.sonrise.alerting.repository.UserCategoryRepository;
import com.sonrise.alerting.repository.UserChannelRepository;
import jakarta.persistence.EntityManager;
import org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the Liquibase schema, the JPA mappings and the repository queries together:
 * seed data, round trips, and that the database itself (not just Java code) rejects invalid data.
 *
 * <p>Persisting and loading go through the repositories. The {@link EntityManager} is only used
 * to clear the persistence context (so reloads really hit the database) and for raw SQL that
 * the entities cannot express (e.g. a link to a user that doesn't exist).
 */
@DataJpaTest
class PersistenceMappingTest {

    @Autowired private AppUserRepository users;
    @Autowired private CategoryRepository categories;
    @Autowired private ChannelRepository channels;
    @Autowired private UserCategoryRepository subscriptions;
    @Autowired private UserChannelRepository userChannels;
    @Autowired private EventRepository events;
    @Autowired private NotificationRepository notifications;
    @Autowired private EntityManager entityManager;

    @Test
    void seedsCategoriesAndChannels() {
        assertThat(categories.findAll()).extracting(Category::getCode)
                .containsExactlyInAnyOrder("BREAKING_NEWS", "MARKETS", "NATURAL_DISASTERS");
        assertThat(channels.findAll()).extracting(Channel::getCode)
                .containsExactlyInAnyOrder("EMAIL", "SLACK");
        assertThat(channels.findAll()).allSatisfy(channel -> assertThat(channel.isEnabled()).isTrue());
    }

    @Test
    void persistsUserWithSubscriptionAndChannelLink() {
        AppUser user = users.saveAndFlush(new AppUser("Alice"));
        subscriptions.save(new UserCategory(user, category("MARKETS"), Severity.HIGH));
        userChannels.save(new UserChannel(user, channel("EMAIL"), "alice@example.com"));
        flushAndClear();

        AppUser reloaded = users.findById(user.getId()).orElse(null);
        UserCategory subscription = subscriptions
                .findById(new UserCategoryId(user.getId(), category("MARKETS").getId())).orElse(null);
        UserChannel link = userChannels
                .findById(new UserChannelId(user.getId(), channel("EMAIL").getId())).orElse(null);

        assertThat(reloaded).isNotNull();
        assertThat(subscription).isNotNull();
        assertThat(link).isNotNull();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(subscription.getMinSeverity()).isEqualTo(Severity.HIGH);
        assertThat(link.getAddress()).isEqualTo("alice@example.com");
        assertThat(link.isEnabled()).isTrue();
        // Enums must be stored by name, not ordinal, so reordering them can't corrupt data.
        assertThat(nativeValue("select min_severity from user_category where user_id = " + user.getId()))
                .isEqualTo("HIGH");
    }

    @Test
    void rejectsDuplicateEventFromSameSource() {
        events.saveAndFlush(event("USGS", "us7000abcd"));

        assertViolates("UQ_EVENT_SOURCE_EXTERNAL_ID", () -> events.saveAndFlush(event("USGS", "us7000abcd")));
    }

    @Test
    void allowsSameExternalIdFromDifferentSources() {
        events.saveAndFlush(event("USGS", "42"));
        events.saveAndFlush(event("RSS", "42"));

        assertThat(events.existsBySourceAndExternalId("USGS", "42")).isTrue();
        assertThat(events.existsBySourceAndExternalId("RSS", "42")).isTrue();
        assertThat(events.existsBySourceAndExternalId("FAKE", "42")).isFalse();
    }

    @Test
    void rejectsDuplicateNotificationForSameEventUserAndChannel() {
        AppUser user = users.save(new AppUser("Bob"));
        Event event = events.save(event("USGS", "us7000efgh"));
        notifications.saveAndFlush(new Notification(event, user, channel("SLACK")));

        assertViolates("UQ_NOTIFICATION_EVENT_USER_CHANNEL",
                () -> notifications.saveAndFlush(new Notification(event, user, channel("SLACK"))));
    }

    @Test
    void rejectsChannelLinkToUnknownUser() {
        assertViolates("FK_USER_CHANNEL_USER", () -> nativeUpdate(
                "insert into user_channel (user_id, channel_id, address) values (999999, "
                        + channel("EMAIL").getId() + ", 'x@example.com')"));
    }

    @Test
    void rejectsChannelLinkWithoutAddress() {
        AppUser user = users.saveAndFlush(new AppUser("Carol"));

        assertViolates("ADDRESS", () -> nativeUpdate(
                "insert into user_channel (user_id, channel_id) values ("
                        + user.getId() + ", " + channel("EMAIL").getId() + ")"));
    }

    @Test
    void deletingUserRemovesSubscriptionsAndChannelLinks() {
        AppUser user = users.saveAndFlush(new AppUser("Erin"));
        subscriptions.save(new UserCategory(user, category("BREAKING_NEWS"), Severity.LOW));
        userChannels.save(new UserChannel(user, channel("SLACK"), "https://hooks.slack.com/services/T/B/X"));
        flushAndClear();

        users.deleteById(user.getId());
        users.flush();

        assertThat(countForUser("user_category", user.getId())).isZero();
        assertThat(countForUser("user_channel", user.getId())).isZero();
    }

    @Test
    void deletingUserWithDeliveryHistoryIsRejected() {
        AppUser user = users.save(new AppUser("Frank"));
        Event event = events.save(event("USGS", "us7000ijkl"));
        notifications.saveAndFlush(new Notification(event, user, channel("EMAIL")));
        entityManager.clear();

        assertViolates("FK_NOTIFICATION_USER", () -> {
            users.deleteById(user.getId());
            users.flush();
        });
    }

    @Test
    void newNotificationStartsPendingWithNoAttempts() {
        AppUser user = users.save(new AppUser("Grace"));
        Event event = events.save(event("USGS", "us7000mnop"));
        Notification notification = notifications.saveAndFlush(new Notification(event, user, channel("EMAIL")));
        entityManager.clear();

        Notification reloaded = notifications.findById(notification.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(reloaded.getAttempts()).isZero();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getSentAt()).isNull();
    }

    @Test
    void findWithCategoryByIdLoadsTheCategoryEagerly() {
        Event event = events.saveAndFlush(event("USGS", "us7000qrst"));
        entityManager.clear();

        Event reloaded = events.findWithCategoryById(event.getId()).orElseThrow();

        // Channels read the category name after the transaction has ended (D26).
        assertThat(Hibernate.isInitialized(reloaded.getCategory())).isTrue();
        assertThat(reloaded.getCategory().getName()).isEqualTo("Natural disasters");
    }

    @Test
    void findDueReturnsOnlyDueNotificationsInStatusOldestFirstUpToLimit() {
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        AppUser user = users.save(new AppUser("Heidi"));
        Notification dueLater = failed(user, "a", now.minusSeconds(10));
        Notification dueFirst = failed(user, "b", now.minusSeconds(60));
        Notification dueExactlyNow = failed(user, "c", now);
        failed(user, "d", now.plusSeconds(1));                      // not due yet
        Notification permanent = failed(user, "e", now.minusSeconds(120));
        permanent.markFailed("gave up", null);                      // FAILED_PERMANENTLY, not FAILED
        notifications.saveAndFlush(permanent);
        entityManager.clear();

        List<Notification> due = notifications.findDue(NotificationStatus.FAILED, now, PageRequest.of(0, 10));
        List<Notification> firstTwo = notifications.findDue(NotificationStatus.FAILED, now, PageRequest.of(0, 2));

        assertThat(due).extracting(Notification::getId)
                .containsExactly(dueFirst.getId(), dueLater.getId(), dueExactlyNow.getId());
        assertThat(firstTwo).extracting(Notification::getId)
                .containsExactly(dueFirst.getId(), dueLater.getId());
        // Everything needed to send outside the transaction is loaded.
        assertThat(due).allSatisfy(n -> {
            assertThat(Hibernate.isInitialized(n.getEvent())).isTrue();
            assertThat(Hibernate.isInitialized(n.getEvent().getCategory())).isTrue();
            assertThat(Hibernate.isInitialized(n.getChannel())).isTrue();
        });
    }

    private Notification failed(AppUser user, String externalId, Instant nextAttemptAt) {
        Event event = events.save(event("USGS", externalId));
        Notification notification = new Notification(event, user, channel("EMAIL"));
        notification.markFailed("SMTP down", nextAttemptAt);
        return notifications.saveAndFlush(notification);
    }

    /**
     * The database itself must reject the change, with a real constraint violation for the
     * named constraint or column. Checking the name alone once passed for the wrong reason (D20).
     * Only the root cause is checked: repositories wrap it in Spring's
     * {@code DataIntegrityViolationException}, raw SQL through the EntityManager in Hibernate's.
     */
    private static void assertViolates(String constraintOrColumn, ThrowingCallable change) {
        assertThatThrownBy(change)
                .rootCause()
                .isInstanceOf(JdbcSQLIntegrityConstraintViolationException.class)
                .hasMessageContaining(constraintOrColumn);
    }

    private Category category(String code) {
        return categories.findByCode(code).orElseThrow();
    }

    private Channel channel(String code) {
        return channels.findByCode(code).orElseThrow();
    }

    private Event event(String source, String externalId) {
        Instant now = Instant.now();
        return new Event(category("NATURAL_DISASTERS"), source, externalId, "M 6.1 - somewhere",
                null, null, Severity.HIGH, now, now);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private void nativeUpdate(String sql) {
        entityManager.createNativeQuery(sql).executeUpdate();
    }

    private Object nativeValue(String sql) {
        return entityManager.createNativeQuery(sql).getSingleResult();
    }

    private long countForUser(String table, Long userId) {
        return ((Number) nativeValue("select count(*) from " + table + " where user_id = " + userId)).longValue();
    }
}
