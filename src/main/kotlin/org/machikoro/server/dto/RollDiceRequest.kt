
package org.machikoro.server.dto

data class RollDiceRequest(
    val gameId: Int,
    val playerId: Int,
    val rollTwoDice: Boolean = false
)
