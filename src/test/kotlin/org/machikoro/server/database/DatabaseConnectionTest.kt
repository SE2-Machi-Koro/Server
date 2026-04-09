package org.machikoro.server.database

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@SpringBootTest
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