package com.sonrise.alerting.domain;

import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the Liquibase schema and the JPA mappings together: seed data, round trips,
 * and that the database itself (not just Java code) rejects invalid data.
 */
@DataJpaTest
class PersistenceMappingTest {

    @Autowired
    private TestEntityManager em;

    @Test
    void seedsCategoriesAndChannels() {
        List<String> categories = em.getEntityManager()
                .createQuery("select c.code from Category c order by c.code", String.class)
                .getResultList();
        List<String> channels = em.getEntityManager()
                .createQuery("select c.code from Channel c order by c.code", String.class)
                .getResultList();

        assertThat(categories).containsExactly("BREAKING_NEWS", "MARKETS", "NATURAL_DISASTERS");
        assertThat(channels).containsExactly("EMAIL", "SLACK");
    }

    @Test
    void persistsUserWithSubscriptionAndChannelLink() {
        AppUser user = em.persistAndFlush(new AppUser("Alice"));
        em.persist(new UserCategory(user, category("MARKETS"), Severity.HIGH));
        em.persist(new UserChannel(user, channel("EMAIL"), "alice@example.com"));
        em.flush();
        em.clear();

        AppUser reloaded = em.find(AppUser.class, user.getId());
        UserCategory subscription = em.find(UserCategory.class,
                new UserCategoryId(user.getId(), category("MARKETS").getId()));
        UserChannel link = em.find(UserChannel.class,
                new UserChannelId(user.getId(), channel("EMAIL").getId()));

        assertThat(reloaded).isNotNull();
        assertThat(subscription).isNotNull();
        assertThat(link).isNotNull();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(subscription.getMinSeverity()).isEqualTo(Severity.HIGH);
        assertThat(link.getAddress()).isEqualTo("alice@example.com");
        assertThat(link.isEnabled()).isTrue();
        // Enums must be stored by name, not ordinal, so reordering them can't corrupt data.
        Object stored = em.getEntityManager()
                .createNativeQuery("select min_severity from user_category where user_id = " + user.getId())
                .getSingleResult();
        assertThat(stored).isEqualTo("HIGH");
    }

    @Test
    void rejectsDuplicateEventFromSameSource() {
        em.persistAndFlush(event("USGS", "us7000abcd"));

        assertThatThrownBy(() -> em.persistAndFlush(event("USGS", "us7000abcd")))
                .isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("UQ_EVENT_SOURCE_EXTERNAL_ID");
    }

    @Test
    void allowsSameExternalIdFromDifferentSources() {
        em.persistAndFlush(event("USGS", "42"));
        em.persistAndFlush(event("RSS", "42"));
    }

    @Test
    void rejectsDuplicateNotificationForSameEventUserAndChannel() {
        AppUser user = em.persist(new AppUser("Bob"));
        Event event = em.persist(event("USGS", "us7000efgh"));
        em.persistAndFlush(new Notification(event, user, channel("SLACK")));

        assertThatThrownBy(() -> em.persistAndFlush(new Notification(event, user, channel("SLACK"))))
                .isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("UQ_NOTIFICATION_EVENT_USER_CHANNEL");
    }

    @Test
    void rejectsChannelLinkToUnknownUser() {
        assertThatThrownBy(() -> nativeUpdate(
                "insert into user_channel (user_id, channel_id, address) values (999999, "
                        + channel("EMAIL").getId() + ", 'x@example.com')"))
                .isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("FK_USER_CHANNEL_USER");
    }

    @Test
    void rejectsChannelLinkWithoutAddress() {
        AppUser user = em.persistAndFlush(new AppUser("Carol"));

        assertThatThrownBy(() -> nativeUpdate(
                "insert into user_channel (user_id, channel_id) values ("
                        + user.getId() + ", " + channel("EMAIL").getId() + ")"))
                .isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("ADDRESS");
    }

    @Test
    void deletingUserRemovesSubscriptionsAndChannelLinks() {
        AppUser user = em.persistAndFlush(new AppUser("Erin"));
        em.persist(new UserCategory(user, category("BREAKING_NEWS"), Severity.LOW));
        em.persist(new UserChannel(user, channel("SLACK"), "https://hooks.slack.com/services/T/B/X"));
        em.flush();
        em.clear();

        em.remove(em.find(AppUser.class, user.getId()));
        em.flush();

        assertThat(count("user_category", user.getId())).isZero();
        assertThat(count("user_channel", user.getId())).isZero();
    }

    @Test
    void deletingUserWithDeliveryHistoryIsRejected() {
        AppUser user = em.persist(new AppUser("Frank"));
        Event event = em.persist(event("USGS", "us7000ijkl"));
        em.persistAndFlush(new Notification(event, user, channel("EMAIL")));
        em.clear();

        assertThatThrownBy(() -> {
            em.remove(em.find(AppUser.class, user.getId()));
            em.flush();
        })
                .isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("FK_NOTIFICATION_USER");
    }

    @Test
    void newNotificationStartsPendingWithNoAttempts() {
        AppUser user = em.persist(new AppUser("Grace"));
        Event event = em.persist(event("USGS", "us7000mnop"));
        Notification notification = em.persistAndFlush(new Notification(event, user, channel("EMAIL")));
        em.clear();

        Notification reloaded = em.find(Notification.class, notification.getId());
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(reloaded.getAttempts()).isZero();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getSentAt()).isNull();
    }

    private Category category(String code) {
        return em.getEntityManager()
                .createQuery("select c from Category c where c.code = :code", Category.class)
                .setParameter("code", code)
                .getSingleResult();
    }

    private Channel channel(String code) {
        return em.getEntityManager()
                .createQuery("select c from Channel c where c.code = :code", Channel.class)
                .setParameter("code", code)
                .getSingleResult();
    }

    private Event event(String source, String externalId) {
        Instant now = Instant.now();
        return new Event(category("NATURAL_DISASTERS"), source, externalId, "M 6.1 - somewhere",
                null, null, Severity.HIGH, now, now);
    }

    private void nativeUpdate(String sql) {
        em.getEntityManager().createNativeQuery(sql).executeUpdate();
    }

    private long count(String table, Long userId) {
        return ((Number) em.getEntityManager()
                .createNativeQuery("select count(*) from " + table + " where user_id = " + userId)
                .getSingleResult()).longValue();
    }
}
