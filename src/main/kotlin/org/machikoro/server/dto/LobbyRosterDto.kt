package org.machikoro.server.dto

data class LobbyRosterDto(
    val players: List<LobbyRosterPlayerDto>,
)

data class LobbyRosterPlayerDto(
    val playerId: Int,
    val userId: Int,
    val username: String,
    val gameId: Int,
    val turnOrder: Int,
    val coins: Int,
    // Populated from in-memory ready state, not from DB
    val isReady: Boolean = false,
)