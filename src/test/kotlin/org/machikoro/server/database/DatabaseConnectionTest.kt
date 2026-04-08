package org.machikoro.server.database

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@SpringBootTest
abstract class AbstractDBSetup {

    companion object {
        @Container
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.0")

        @DynamicPropertySource
        @JvmStatic
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("DB_HOST") { postgres.host }
            registry.add("DB_PORT") { postgres.firstMappedPort.toString() }
            registry.add("DB_NAME") { postgres.databaseName }
            registry.add("DB_USERNAME") { postgres.username }
            registry.add("DB_PASSWORD") { postgres.password }
        }
    }
}

class DatabaseConnectionTest : AbstractDBSetup() {

    @Test
    fun databaseConnectionAndSchemaCreationSucceeds() {
        transaction {
            val tables = SchemaUtils.listTables().map { it.substringAfterLast(".") }
            val expectedTables = listOf(
                "users",
                "games",
                "players",
                "player_cards",
                "player_landmarks",
                "game_marketplace"
            )

            val missingTables = expectedTables.filterNot { tables.contains(it) }

            assertTrue(
                missingTables.isEmpty(),
                "Missing expected tables: $missingTables \nActually found these tables in the DB: $tables"
            )
        }
    }
}