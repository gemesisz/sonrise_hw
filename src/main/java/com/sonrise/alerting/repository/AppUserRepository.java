package com.sonrise.alerting.repository;

import com.sonrise.alerting.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
}
