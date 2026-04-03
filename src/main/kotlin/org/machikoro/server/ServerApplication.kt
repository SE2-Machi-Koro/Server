package org.machikoro.server

import io.github.cdimascio.dotenv.Dotenv
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.database.GameMarketplace
import org.machikoro.server.database.GameStatus
import org.machikoro.server.database.Games
import org.machikoro.server.database.PlayerCards
import org.machikoro.server.database.PlayerLandmarks
import org.machikoro.server.database.Players
import org.machikoro.server.database.Users
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class ServerApplication {

    @Bean
    fun init() = CommandLineRunner {
        println("Connecting to DB...")
        connectToDatabase()
        println("Connected!")
    }
}

fun connectToDatabase() {

    val host = System.getenv("DB_HOST") ?: "localhost"
    val port = System.getenv("DB_PORT") ?: "5432"
    val dbName = System.getenv("DB_NAME") ?: "machikoro"
    val user = System.getenv("DB_USERNAME") ?: throw IllegalArgumentException("Missing DB_USERNAME")
    val password = System.getenv("DB_PASSWORD") ?: throw IllegalArgumentException("Missing DB_PASSWORD")

    val jdbcUrl = "jdbc:postgresql://$host:$port/$dbName"

    Database.connect(
        url = jdbcUrl,
        driver = "org.postgresql.Driver",
        user = user,
        password = password
    )

    transaction {
        SchemaUtils.create(Users, Games, Players, PlayerCards, PlayerLandmarks, GameMarketplace)
    }
}

fun main(args: Array<String>) {
    runApplication<ServerApplication>(*args)
}
