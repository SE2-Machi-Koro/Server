package org.machikoro.server.database

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import javax.sql.DataSource

@Component
@ConditionalOnProperty(name = ["machikoro.db.init.enabled"], havingValue = "true", matchIfMissing = true)
class DatabaseInitializer(private val dataSource: DataSource) : CommandLineRunner {

    override fun run(vararg args: String) {
        initDatabase(dataSource)
    }
}

fun initDatabase(dataSource: DataSource) {
    Database.connect(dataSource)

    transaction {
        SchemaUtils.create(
            Users, Games, Players,
            PlayerCards, PlayerLandmarks, GameMarketplace
        )
    }
}