package org.machikoro.server.dto

import org.machikoro.server.domain.enums.TurnPhase

data class RollDiceResponse(
    val dice: List<Int>,
    val total: Int,
    val completed: Boolean = true,
    val turnPhase: TurnPhase = TurnPhase.RESOLVE_EFFECTS,
)
