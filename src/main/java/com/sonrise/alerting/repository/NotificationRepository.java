package com.sonrise.alerting.repository;

import com.sonrise.alerting.domain.Notification;
import com.sonrise.alerting.domain.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    /**
     * One notification with everything needed to send it outside the transaction.
     */
    @Query("""
            select n from Notification n
              join fetch n.event e
              join fetch e.category
              join fetch n.user
              join fetch n.channel
             where n.id = :id
            """)
    Optional<Notification> findWithDetailsById(Long id);

    /**
     * Admin list. {@code status} and {@code userId} are optional (null = any).
     */
    @Query(value = """
            select n from Notification n
              join fetch n.event
              join fetch n.user u
              join fetch n.channel
             where (:status is null or n.status = :status)
               and (:userId is null or u.id = :userId)
            """,
            countQuery = """
            select count(n) from Notification n
             where (:status is null or n.status = :status)
               and (:userId is null or n.user.id = :userId)
            """)
    Page<Notification> search(NotificationStatus status, Long userId, Pageable pageable);

    /**
     * Deleting a user deletes their delivery history (admin decision, D43). Bulk delete: the
     * persistence context is not updated, so call it before loading/deleting the user.
     */
    @Modifying
    @Query("delete from Notification n where n.user.id = :userId")
    int deleteByUserId(Long userId);
}
