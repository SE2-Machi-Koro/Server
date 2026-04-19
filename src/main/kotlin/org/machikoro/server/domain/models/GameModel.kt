package org.machikoro.server.domain.models

import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase

data class GameModel(
    val id: Int,
    val status: GameStatus,
    val hostUserId: Int,
    val currentTurnIndex: Int,
    val turnPhase: TurnPhase,
    val lastDiceRoll: Int?,
    val roundNumber: Int
)