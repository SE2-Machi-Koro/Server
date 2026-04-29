package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.PlayerModel
import org.springframework.stereotype.Service

@Service
class GamePhaseService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val gameStateGuard: GameStateGuard,
    private val winConditionService: WinConditionService
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

    /** Advances a game to the next phase and persists it.
     * Sets new values such as next PLayer index, updated round
     * number and initial phase */
    private fun advancePhase(gameId: Int): TurnPhase {
        val game = gameStateGuard.ensureGameIsRunning(gameId)
        val players = playerDao.getPlayers(gameId)
        check(players.isNotEmpty()) { "Game $gameId has no players" }
        val nextTurnIndex = (game.currentTurnIndex + 1) % players.size
        val nextRoundNumber = if (nextTurnIndex == 0) game.roundNumber + 1 else game.roundNumber
        gameDao.advanceTurn(gameId, nextTurnIndex, nextRoundNumber)
        return game.turnPhase
    }

    /** Ends the active player's buy-or-build window and starts the next turn. */
    fun endTurn(gameId: Int): EndTurnOutcome {
        val game = gameStateGuard.ensureGameIsRunning(gameId)
        check(game.turnPhase == TurnPhase.BUY_OR_BUILD) { "Game is not in BUY_OR_BUILD phase" }

        winConditionService.detectWinner(gameId)?.let { winner ->
            gameDao.updateStatus(gameId, GameStatus.FINISHED)
            finishGame(gameId)
            return EndTurnOutcome.Won(winner)
        }

        val nextPhase = advancePhase(gameId)
        return EndTurnOutcome.Continue(nextPhase)
    }

    fun finishGame(gameId: Int) {
        gameDao.updateStatus(gameId, GameStatus.FINISHED)
    }

    sealed interface EndTurnOutcome {
        data class Continue(
            val nextPhase: TurnPhase,
        ) : EndTurnOutcome

        data class Won(
            val winner: PlayerModel
        ) : EndTurnOutcome
    }
}
