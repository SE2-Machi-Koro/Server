package org.machikoro.server.dto

data class LeaveFinishedGameRequest(
    val gameId: Int,
    val playerId: Int
)
