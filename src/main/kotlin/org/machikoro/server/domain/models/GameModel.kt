package org.machikoro.server.domain.models

import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase

/**
 * Domain model representing data used in the application's core logic
 * - Encapsulates pure business data independent of persistence and frameworks
 * - Used by the service layer to implement game logic
 * - Can be used for DTO's
 * - Does not contain database logic
 * - Used to represent the game state
 */
data class GameModel(
    val id: Int,
    val status: GameStatus,
    val hostUserId: Int,
    val lobbyCode: String,   // used by players to join the lobby
    val maxPlayers: Int,     // maximum number of players allowed
    val currentTurnIndex: Int,
    val turnPhase: TurnPhase,
    val lastDiceRoll: Int?,
    val roundNumber: Int
)