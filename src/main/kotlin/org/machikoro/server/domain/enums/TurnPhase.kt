package org.machikoro.server.domain.enums

enum class TurnPhase {
    ROLL_DICE,
    RESOLVE_EFFECTS,
    // Active player activated a TV Station (CHOSEN_PLAYER) and must pick the
    // opponent to steal 5 coins from before the turn advances. See issue #433.
    AWAIT_TV_TARGET,
    BUY_OR_BUILD,
    END_TURN
}