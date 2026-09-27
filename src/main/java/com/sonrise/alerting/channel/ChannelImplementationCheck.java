package com.sonrise.alerting.channel;

import com.sonrise.alerting.domain.Channel;
import com.sonrise.alerting.repository.ChannelRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Stops the application at startup if the {@code channel} table and the {@link NotificationChannel}
 * implementations disagree: a row without an implementation could never deliver, and an
 * implementation without a row could never be linked to a user. Adding a channel means adding both.
 */
@Component
public class ChannelImplementationCheck implements ApplicationRunner {

    private final ChannelRepository channelRepository;
    private final List<NotificationChannel> implementations;

    public ChannelImplementationCheck(ChannelRepository channelRepository, List<NotificationChannel> implementations) {
        this.channelRepository = channelRepository;
        this.implementations = implementations;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> rows = channelRepository.findAll().stream().map(Channel::getCode).collect(Collectors.toSet());
        Set<String> beans = implementations.stream().map(NotificationChannel::code).collect(Collectors.toSet());

        Set<String> withoutImplementation = new TreeSet<>(rows);
        withoutImplementation.removeAll(beans);
        Set<String> withoutRow = new TreeSet<>(beans);
        withoutRow.removeAll(rows);
        if (!withoutImplementation.isEmpty() || !withoutRow.isEmpty()) {
            throw new IllegalStateException("Channel configuration mismatch: channel rows without an implementation "
                    + withoutImplementation + ", implementations without a channel row " + withoutRow
                    + ". Add the missing Liquibase seed row or NotificationChannel implementation.");
        }
    }
}
