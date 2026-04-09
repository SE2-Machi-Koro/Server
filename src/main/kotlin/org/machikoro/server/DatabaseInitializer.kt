package org.machikoro.server

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.database.GameMarketplace
import org.machikoro.server.database.Games
import org.machikoro.server.database.PlayerCards
import org.machikoro.server.database.PlayerLandmarks
import org.machikoro.server.database.Players
import org.machikoro.server.database.Users
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import javax.sql.DataSource

@Component
@ConditionalOnProperty(name = ["machikoro.db.init.enabled"], havingValue = "true", matchIfMissing = true)
class DatabaseInitializer(private val dataSource: DataSource) : CommandLineRunner {

    override fun run(vararg args: String) {
        connectToDatabase(dataSource)
    }
}

fun connectToDatabase(dataSource: DataSource) {
    Database.connect(dataSource)

    transaction {
        SchemaUtils.create(
            Users, Games, Players,
            PlayerCards, PlayerLandmarks, GameMarketplace
        )
    }
}