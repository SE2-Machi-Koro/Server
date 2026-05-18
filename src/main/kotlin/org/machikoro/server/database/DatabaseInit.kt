package org.machikoro.server.database

import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * Registers the application's [DataSource] with Exposed's [Database] singleton
 * so every `transaction { ... }` block in the DAOs has a default database to
 * run against. Without this, the first DB-touching request throws
 * "No database specified and no default database found".
 *
 * Schema creation is owned by Flyway (`src/main/resources/db/migration`),
 * which runs automatically on Spring Boot startup before this runner. This
 * class only wires Exposed to the already-migrated DataSource.
 *
 * Gated by `machikoro.db.init.enabled` so tests that boot the Spring context
 * without a DataSource (`HealthEndpointTest`, `SecurityConfigTests` via
 * `@SpringBootTestWithoutDataSource`) can disable the bean. Production must
 * leave it enabled — without it every DAO call fails.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = ["machikoro.db.init.enabled"], havingValue = "true", matchIfMissing = true)
class DatabaseInitializer(private val dataSource: DataSource) : CommandLineRunner {

    override fun run(vararg args: String) {
        initDatabase(dataSource)
    }
}

/**
 * Wires Exposed to [dataSource]. Idempotent — safe to call multiple times.
 * Does not create or modify schema; Flyway handles that.
 */
fun initDatabase(dataSource: DataSource) {
    Database.connect(dataSource)
}