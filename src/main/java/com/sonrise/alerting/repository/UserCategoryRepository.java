package com.sonrise.alerting.repository;

import com.sonrise.alerting.domain.UserCategory;
import com.sonrise.alerting.domain.UserCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface UserCategoryRepository extends JpaRepository<UserCategory, UserCategoryId> {

    @Query("select s from UserCategory s join fetch s.category where s.user.id in :userIds")
    List<UserCategory> findWithCategoryByUserIdIn(Collection<Long> userIds);
}
