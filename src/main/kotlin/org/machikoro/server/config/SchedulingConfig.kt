package org.machikoro.server.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Activates Spring's scheduling infrastructure for production and development
 * environments.  Excluded from the "test" profile so that [org.springframework.scheduling.annotation.Scheduled]
 * tasks — in particular [org.machikoro.server.service.WebSocketConnectionTracker.evictStaleSessions] —
 * never fire during the test run.  This prevents spurious background threads
 * from consuming heap and interfering with test assertions.
 */
@Configuration
@EnableScheduling
@Profile("!test")
class SchedulingConfig
