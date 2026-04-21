package org.machikoro.server.domain.models

/**
 * Domain model representing data used in the application's core logic
 * - Encapsulates pure business data independent of persistence and frameworks
 * - Used by the service layer to implement game logic
 * - Can be used for DTO's
 * - Does not contain database logic
 * - Used to represent the game state
 */
data class PlayerModel(
    val id: Int,
    val gameId: Int,
    val userId: Int,
    val turnOrder: Int,
    val coins: Int
)