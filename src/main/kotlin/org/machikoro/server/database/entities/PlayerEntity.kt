package org.machikoro.server.database.entities

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.machikoro.server.database.Players
import org.machikoro.server.domain.models.PlayerModel

/*
Exposed DAO entity representing a single row in corresponding database table
- Acts as an object-oriented wrapper around database row
- Provides direct-acces to table columns via delegated properties
- Allows navigation of relationships
- Encapsulation persistence logic handled by Exposed

Entities are part of the database layer and should not be exposed outside
They are converted into domain models using toModel()
 */
class PlayerEntity(id: EntityID<Int>) : IntEntity(id) {
    /*
    Companion object required by Exposed DAO
    - Acts as a factory for creating and querying entities
    */
    companion object : IntEntityClass<PlayerEntity>(Players)

    var game by GameEntity referencedOn Players.gameId
    var user by UserEntity referencedOn Players.userId
    var turnOrder by Players.turnOrder
    var coins by Players.coins

    /*
    Converts this database entity into a domain model
    Ensures:
    - Separation between persistence and business layer
    - Domain model remain independent of Exposed/DB concerns
     */
    fun toModel() = PlayerModel(
        id = id.value,
        gameId = game.id.value,
        userId = user.id.value,
        turnOrder = turnOrder,
        coins = coins
    )
}