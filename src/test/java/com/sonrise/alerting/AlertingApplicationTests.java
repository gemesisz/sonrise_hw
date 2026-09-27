package com.sonrise.alerting;

import com.sonrise.alerting.channel.EmailChannel;
import com.sonrise.alerting.channel.NotificationChannelRegistry;
import com.sonrise.alerting.channel.SlackChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AlertingApplicationTests {

    @Autowired
    private NotificationChannelRegistry channelRegistry;

    @Test
    void contextLoads() {
    }

    @Test
    void registersEmailAndSlackChannelBeans() {
        assertThat(channelRegistry.get("EMAIL")).isInstanceOf(EmailChannel.class);
        assertThat(channelRegistry.get("SLACK")).isInstanceOf(SlackChannel.class);
    }
}
