package org.machikoro.server.database

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@SpringBootTest
class DatabaseConnectionTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.0")

        @BeforeAll
        @JvmStatic
        fun setupProperties() {
            System.setProperty("DB_HOST", postgres.host)
            System.setProperty("DB_PORT", postgres.firstMappedPort.toString())
            System.setProperty("DB_NAME", postgres.databaseName)
            System.setProperty("DB_USERNAME", postgres.username)
            System.setProperty("DB_PASSWORD", postgres.password)
        }
    }

    @Test
    fun databaseConnectionAndSchemaCreationSucceeds() {
        transaction {
            val tables = SchemaUtils.listTables()
            assertTrue(tables.isNotEmpty())
        }
    }
}