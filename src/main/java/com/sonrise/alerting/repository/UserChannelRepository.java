package com.sonrise.alerting.repository;

import com.sonrise.alerting.domain.Severity;
import com.sonrise.alerting.domain.UserChannel;
import com.sonrise.alerting.domain.UserChannelId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface UserChannelRepository extends JpaRepository<UserChannel, UserChannelId> {

    /**
     * Where an event must be delivered: every enabled channel link (on an enabled channel) of every
     * user subscribed to the category with a minimum severity in {@code severities}.
     */
    @Query("""
            select uc from UserChannel uc
              join fetch uc.user u
              join fetch uc.channel c
             where uc.enabled = true
               and c.enabled = true
               and u.id in (select s.user.id from UserCategory s
                             where s.category.id = :categoryId
                               and s.minSeverity in :severities)
            """)
    List<UserChannel> findDeliveryTargets(Long categoryId, Collection<Severity> severities);
}
