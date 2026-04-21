package org.machikoro.server.domain.models

/**
 * Domain model representing data used in the application's core logic
 * - Encapsulates pure business data independent of persistence and frameworks
 * - Used by the service layer to implement game logic
 * - Can be used for DTO's
 * - Does not contain database logic
 * - Used to represent the game state
 */
data class UserModel(
    val id: Int,
    val username: String,
    val sessionToken: String?,
    val totalWins: Int,
    val totalGamesPlayed: Int
)