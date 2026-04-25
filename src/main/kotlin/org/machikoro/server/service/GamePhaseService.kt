package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.TurnPhase
import org.springframework.stereotype.Service

@Service
class GamePhaseService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
) {

    /** Returns the next phase in the Machi Koro turn cycle. */
    fun nextPhase(currentPhase: TurnPhase): TurnPhase = when (currentPhase) {
        TurnPhase.ROLL_DICE -> TurnPhase.RESOLVE_EFFECTS
        TurnPhase.RESOLVE_EFFECTS -> TurnPhase.BUY_OR_BUILD
        TurnPhase.BUY_OR_BUILD -> TurnPhase.END_TURN
        TurnPhase.END_TURN -> TurnPhase.ROLL_DICE
    }

    /** Returns the phase that begins every new turn. */
    fun initialPhase(): TurnPhase = TurnPhase.ROLL_DICE

    /** Advances a game to the next phase and persists it. */
    fun advancePhase(gameId: Int): TurnPhase {
        val next = nextPhase(gameDao.getPhase(gameId))
        gameDao.updateTurnPhase(gameId, next)
        return next
    }

    /** Ends the active player's buy-or-build window and starts the next turn. */
    fun endTurn(gameId: Int): TurnPhase {
        val game = gameDao.findById(gameId)
            ?: error("Game $gameId not found")
        check(game.turnPhase == TurnPhase.BUY_OR_BUILD) { "Game is not in BUY_OR_BUILD phase" }

        val players = playerDao.findByGameId(gameId)
        check(players.isNotEmpty()) { "Game $gameId has no players" }

        gameDao.updateTurnPhase(gameId, TurnPhase.END_TURN)

        val nextTurnIndex = (game.currentTurnIndex + 1) % players.size
        val nextRoundNumber = if (nextTurnIndex == 0) game.roundNumber + 1 else game.roundNumber

        gameDao.advanceTurn(gameId, nextTurnIndex, nextRoundNumber)
        return TurnPhase.ROLL_DICE
    }
}
