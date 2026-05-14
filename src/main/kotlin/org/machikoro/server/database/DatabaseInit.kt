package org.machikoro.server.database

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * Registers the application's [DataSource] with Exposed's [Database] singleton
 * so every `transaction { ... }` block in the DAOs has a default database to
 * run against. Without this, the first DB-touching request throws
 * "No database specified and no default database found".
 *
 * Also runs Exposed's [SchemaUtils.create] as a safety net for environments
 * where Flyway hasn't (yet) executed — `CREATE TABLE IF NOT EXISTS` is
 * idempotent so this is a no-op once Flyway has the schema covered. See
 * tracker for the Flyway 11 + `flyway-database-postgresql` follow-up that
 * makes Flyway the sole schema owner.
 *
 * Gated by `machikoro.db.init.enabled` so tests that boot the Spring context
 * without a DataSource (`HealthEndpointTest`, `SecurityConfigTests` via
 * `@SpringBootTestWithoutDataSource`) can disable the bean. Production must
 * leave it enabled — without it every DAO call fails.
 */
@Component
@ConditionalOnProperty(name = ["machikoro.db.init.enabled"], havingValue = "true", matchIfMissing = true)
class DatabaseInitializer(private val dataSource: DataSource) : CommandLineRunner {

    override fun run(vararg args: String) {
        initDatabase(dataSource)
    }
}

/**
 * Wires Exposed to [dataSource] and ensures the schema exists.
 * Idempotent — safe to call multiple times.
 */
fun initDatabase(dataSource: DataSource) {
    Database.connect(dataSource)

    transaction {
        SchemaUtils.create(
            Cards,
            CardActivationNumbers,
            Landmarks,
            Users,
            Games,
            Players,
            PlayerCards,
            PlayerLandmarks,
            GameMarketplace
        )
    }
}
