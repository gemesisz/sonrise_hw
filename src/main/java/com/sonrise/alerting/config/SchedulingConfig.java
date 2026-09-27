package com.sonrise.alerting.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the detection schedule. Off in tests (src/test/resources/config/application.yml),
 * so test contexts never poll the live feeds; {@code runNow()} still works there.
 */
@Configuration
@EnableScheduling
@ConditionalOnBooleanProperty(name = "alerting.detection.scheduling-enabled", matchIfMissing = true)
public class SchedulingConfig {
}
