package org.machikoro.server.domain.models

import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase

data class GameModel(
    val id: Int,
    val status: GameStatus,
    val hostUserId: Int,
    val lobbyCode: String,   // used by players to join the lobby
    val maxPlayers: Int,     // maximum number of players allowed
    val currentTurnIndex: Int,
    val turnPhase: TurnPhase,
    val lastDiceRoll: Int?,
    val roundNumber: Int,
    val hasPurchasedThisTurn: Boolean,
)
