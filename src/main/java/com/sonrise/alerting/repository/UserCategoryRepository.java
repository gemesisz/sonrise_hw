package com.sonrise.alerting.repository;

import com.sonrise.alerting.domain.UserCategory;
import com.sonrise.alerting.domain.UserCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCategoryRepository extends JpaRepository<UserCategory, UserCategoryId> {
}
