package com.sonrise.alerting.repository;

import com.sonrise.alerting.domain.UserChannel;
import com.sonrise.alerting.domain.UserChannelId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserChannelRepository extends JpaRepository<UserChannel, UserChannelId> {
}
