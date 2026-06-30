package org.machikoro.server.dto

import com.fasterxml.jackson.annotation.JsonProperty

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
    // Guard against Jackson stripping "is" prefix without KotlinModule.
    @get:JsonProperty("isReady")
    val isReady: Boolean = false,
)