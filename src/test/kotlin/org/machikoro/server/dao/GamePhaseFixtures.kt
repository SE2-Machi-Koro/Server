package org.machikoro.server.dao

import org.machikoro.server.domain.enums.TurnPhase

/**
 * Shared test fixtures for advancing a freshly-created game through its turn
 * phases via the guarded production path. PR #439 removed the `updateAfterRoll`
 * bypass, so fixtures must drive [GameDao.tryRecordDiceRoll] /
 * [GameDao.tryTransitionPhase] directly. Centralising the `check(...)`
 * incantation here keeps future phase-transition guard changes in one place.
 */

/**
 * Advances a game from ROLL_DICE into RESOLVE_EFFECTS by recording a dice roll.
 * Fails fast if the game is not in ROLL_DICE.
 */
fun GameDao.advanceToResolveEffects(gameId: Int, diceRoll: Int, diceCount: Int) {
    check(tryRecordDiceRoll(gameId, diceRoll = diceRoll, diceCount = diceCount)) {
        "fixture: game not in ROLL_DICE"
    }
}

/**
 * Advances a game from ROLL_DICE through RESOLVE_EFFECTS into BUY_OR_BUILD.
 * Fails fast if either guarded transition is rejected.
 */
fun GameDao.advanceToBuyOrBuild(gameId: Int, diceRoll: Int, diceCount: Int) {
    advanceToResolveEffects(gameId, diceRoll = diceRoll, diceCount = diceCount)
    check(tryTransitionPhase(gameId, TurnPhase.RESOLVE_EFFECTS, TurnPhase.BUY_OR_BUILD)) {
        "fixture: game not in RESOLVE_EFFECTS"
    }
}
