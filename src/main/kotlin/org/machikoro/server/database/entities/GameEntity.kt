package org.machikoro.server.database.entities

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.machikoro.server.database.Games
import org.machikoro.server.domain.models.GameModel

class GameEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<GameEntity>(Games)

    var status by Games.status
    var hostUser by UserEntity referencedOn Games.hostUserId
    var currentTurnIndex by Games.currentTurnIndex
    var turnPhase by Games.turnPhase
    var lastDiceRoll by Games.lastDiceRoll
    var roundNumber by Games.roundNumber

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