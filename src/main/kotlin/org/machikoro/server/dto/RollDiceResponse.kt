package org.machikoro.server.dto

data class RollDiceResponse(
    val dice: List<Int>,
    val total: Int
)