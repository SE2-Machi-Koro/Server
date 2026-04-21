package org.machikoro.server.database

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * Initializes database schema when app starts
 * Automatically executed by SpringBoot because:
 * - Marked as a component
 * - Implements CommandLineRunner
 */
@Component
@ConditionalOnProperty(name = ["machikoro.db.init.enabled"], havingValue = "true", matchIfMissing = true)
/**
 * DataSource is provided by Spring and represents the app's configured database connection
 * Handles:
 * - Connection creation
 * - Connection pooling
 */
class DatabaseInitializer(private val dataSource: DataSource) : CommandLineRunner {

    override fun run(vararg args: String) {
        initDatabase(dataSource)
    }
}

/**
 * Initializes database:
 * - Connects Exposed to the database via DataSource
 * - Creates all tables if they do not exist yet
 */
fun initDatabase(dataSource: DataSource) {
    Database.connect(dataSource)

    /**
     * Runs all schema operations inside a transaction
     * Ensures:
     * - All table creations succeed together
     * - If something fails -> Rollback
     */
    transaction {
        SchemaUtils.create(
            Cards,
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