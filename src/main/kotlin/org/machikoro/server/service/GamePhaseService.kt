package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.PurchaseType
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

    /** Advances a game to the next phase and persists it. */
    fun advancePhase(gameId: Int): TurnPhase {
        val game = gameStateGuard.ensureGameIsRunning(gameId)
        val next = nextPhase(game.turnPhase)
        gameDao.updateTurnPhase(gameId, next)
        return next
    }

    /** Ends the active player's buy-or-build window and starts the next turn. */
    fun endTurn(gameId: Int): EndTurnOutcome {
        val game = gameStateGuard.ensureGameIsRunning(gameId)
        check(game.turnPhase == TurnPhase.BUY_OR_BUILD) { "Game is not in BUY_OR_BUILD phase" }
        val players = playerDao.getPlayers(gameId)
        check(players.isNotEmpty()) { "Game $gameId has no players" }

        winConditionService.detectWinner(gameId)?.let { winner ->
            gameDao.updateStatus(gameId, GameStatus.FINISHED)
            return EndTurnOutcome.Won(winner)
        }
        gameDao.updateTurnPhase(gameId, TurnPhase.END_TURN)

        val nextTurnIndex = (game.currentTurnIndex + 1) % players.size
        val nextRoundNumber = if (nextTurnIndex == 0) game.roundNumber + 1 else game.roundNumber

        gameDao.advanceTurn(gameId, nextTurnIndex, nextRoundNumber)
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
