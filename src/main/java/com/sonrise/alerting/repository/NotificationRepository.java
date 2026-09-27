package com.sonrise.alerting.repository;

import com.sonrise.alerting.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
}
