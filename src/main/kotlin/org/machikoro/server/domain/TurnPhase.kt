package org.machikoro.server.domain

enum class TurnPhase {
    ROLL_DICE,
    RESOLVE_EFFECTS,
    BUY_OR_BUILD,
    END_TURN
}