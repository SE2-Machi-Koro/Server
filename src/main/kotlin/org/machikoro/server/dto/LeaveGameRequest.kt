package org.machikoro.server.dto

data class LeaveGameRequest(
    val gameId: Int,
    val playerId: Int
)
