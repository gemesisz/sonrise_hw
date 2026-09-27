package com.sonrise.alerting.repository;

import com.sonrise.alerting.domain.Notification;
import com.sonrise.alerting.domain.NotificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Notifications in {@code status} whose next attempt is due, oldest first, with everything
     * needed to send them outside the transaction (event + category, user, channel).
     */
    @Query("""
            select n from Notification n
              join fetch n.event e
              join fetch e.category
              join fetch n.user
              join fetch n.channel
             where n.status = :status
               and n.nextAttemptAt <= :now
             order by n.nextAttemptAt
            """)
    List<Notification> findDue(NotificationStatus status, Instant now, Pageable page);
}
