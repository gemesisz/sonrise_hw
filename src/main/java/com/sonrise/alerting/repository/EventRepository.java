package com.sonrise.alerting.repository;

import com.sonrise.alerting.domain.Event;
import com.sonrise.alerting.domain.Severity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    boolean existsBySourceAndExternalId(String source, String externalId);

    /**
     * Loads the category eagerly: channels print its name after the transaction has ended.
     */
    @Query("select e from Event e join fetch e.category where e.id = :id")
    Optional<Event> findWithCategoryById(Long id);

    /**
     * Admin list. {@code categoryCode} and {@code source} are optional (null = any).
     */
    @Query(value = """
            select e from Event e join fetch e.category c
             where (:categoryCode is null or c.code = :categoryCode)
               and (:source is null or e.source = :source)
               and e.severity in :severities
            """,
            countQuery = """
            select count(e) from Event e join e.category c
             where (:categoryCode is null or c.code = :categoryCode)
               and (:source is null or e.source = :source)
               and e.severity in :severities
            """)
    Page<Event> search(String categoryCode, String source, Collection<Severity> severities, Pageable pageable);
}
