package com.finance.command.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's @Scheduled support for the subscription detection job.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
