package com.sonrise.alerting.repository;

import com.sonrise.alerting.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    boolean existsBySourceAndExternalId(String source, String externalId);

    /**
     * Loads the category eagerly: channels print its name after the transaction has ended.
     */
    @Query("select e from Event e join fetch e.category where e.id = :id")
    Optional<Event> findWithCategoryById(Long id);
}
