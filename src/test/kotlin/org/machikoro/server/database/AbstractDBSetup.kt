package org.machikoro.server.database

import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Testcontainers
abstract class AbstractDBSetup {

    companion object {
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.0").also { it.start() }

        @DynamicPropertySource
        @JvmStatic
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("machikoro.db.init.enabled") { "true" }

            Database.connect(
                url      = postgres.jdbcUrl,
                driver   = "org.postgresql.Driver",
                user     = postgres.username,
                password = postgres.password
            )
        }
    }
}