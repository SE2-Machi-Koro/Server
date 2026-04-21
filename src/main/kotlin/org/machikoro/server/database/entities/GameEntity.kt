package org.machikoro.server.database.entities

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.machikoro.server.database.Games
import org.machikoro.server.domain.models.GameModel

/*
Exposed DAO entity representing a single row in corresponding database table
- Acts as an object-oriented wrapper around database row
- Provides direct-acces to table columns via delegated properties
- Allows navigation of relationships
- Encapsulation persistence logic handled by Exposed

Entities are part of the database layer and should not be exposed outside
They are converted into domain models using toModel()
 */
class GameEntity(id: EntityID<Int>) : IntEntity(id) {
    /*
    Companion object required by Exposed DAO
    - Acts as a factory for creating and querying entities
    */
    companion object : IntEntityClass<GameEntity>(Games)

    var status by Games.status
    var hostUser by UserEntity referencedOn Games.hostUserId
    var currentTurnIndex by Games.currentTurnIndex
    var turnPhase by Games.turnPhase
    var lastDiceRoll by Games.lastDiceRoll
    var roundNumber by Games.roundNumber

    /*
    Converts this database entity into a domain model
    Ensures:
    - Separation between persistence and business layer
    - Domain model remain independent of Exposed/DB concerns
     */
    fun toModel() = GameModel(
        id = id.value,
        status = status,
        hostUserId = hostUser.id.value,
        currentTurnIndex = currentTurnIndex,
        turnPhase = turnPhase,
        lastDiceRoll = lastDiceRoll,
        roundNumber = roundNumber
    )
}