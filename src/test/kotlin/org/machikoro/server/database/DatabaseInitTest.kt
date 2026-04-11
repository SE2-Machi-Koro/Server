package org.machikoro.server.database

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.getBean
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.testcontainers.junit.jupiter.Testcontainers
import javax.sql.DataSource

@Testcontainers
@SpringBootTest
class DatabaseInitTest : AbstractDBSetup() {

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `initDatabase ensures all tables exist`() {
        initDatabase(dataSource)

        transaction {
            val tables = SchemaUtils.listTables()
                .map { it.substringAfterLast(".").lowercase() }
                .toSet()

            val expectedTables = setOf(
                "cards",
                "landmarks",
                "users",
                "games",
                "players",
                "player_cards",
                "player_landmarks",
                "game_marketplace"
            )

            val missing = expectedTables - tables

            assertTrue(
                missing.isEmpty(),
                "Missing tables: $missing\nActual tables: $tables"
            )
        }
    }

    @Test
    fun `initDatabase is idempotent`() {
        assertDoesNotThrow {
            initDatabase(dataSource)
            initDatabase(dataSource)
        }

        transaction {
            val tables = SchemaUtils.listTables()
                .map { it.substringAfterLast(".").lowercase() }

            assertTrue(tables.isNotEmpty(), "Tables should still exist after repeated initialization")
        }
    }

    @Test
    fun `DatabaseInitializer bean is present in context`() {
        val beans = applicationContext.getBeansOfType(DatabaseInitializer::class.java)

        assertTrue(
            beans.isNotEmpty(),
            "DatabaseInitializer should be registered as a Spring bean"
        )
    }

    @Test
    fun `DatabaseInitializer runs without exception`() {
        val initializer = applicationContext.getBean<DatabaseInitializer>()

        assertDoesNotThrow {
            initializer.run()
        }
    }
}