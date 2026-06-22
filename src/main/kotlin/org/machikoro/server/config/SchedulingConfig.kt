package org.machikoro.server.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Enables Spring's `@Scheduled` task execution support.
 *
 * Kept as a dedicated configuration class so that scheduling can be
 * excluded from integration tests that do not need background tasks
 * (e.g. by overriding the bean or using `@MockBean`).
 */
@Configuration
@EnableScheduling
class SchedulingConfig
