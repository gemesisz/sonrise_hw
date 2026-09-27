package com.sonrise.alerting.repository;

import com.sonrise.alerting.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {
}
