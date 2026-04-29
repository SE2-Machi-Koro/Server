package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.PlayerModel
import org.springframework.stereotype.Service

@Service
class GamePhaseService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val userDao: UserDao,
    private val playerCardDao: PlayerCardDao,
    private val playerLandmarkDao: PlayerLandmarkDao,
    private val gameMarketplaceDao: GameMarketplaceDao,
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

    // TODO() Prove if needed since advanceTurn always sets ROLL_DICE as current phase
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
        gameDao.updateTurnPhase(gameId,TurnPhase.END_TURN)
        winConditionService.detectWinner(gameId)?.let { winner ->
            userDao.incrementWins(winner.id)
            finishGame(gameId)
            return EndTurnOutcome.Won(winner)
        }
        val nextPhase = advanceTurn(gameId)
        return EndTurnOutcome.Continue(nextPhase)
    }

    /** Advances a game to the next player's turn and persists it.
     * Sets new values such as next PLayer index, updated round
     * number and initial phase */
    private fun advanceTurn(gameId: Int): TurnPhase {
        val game = gameStateGuard.ensureGameIsRunning(gameId)
        val players = playerDao.getPlayers(gameId)
        check(players.isNotEmpty()) { "Game $gameId has no players" }
        val nextTurnIndex = (game.currentTurnIndex + 1) % players.size
        val nextRoundNumber = if (nextTurnIndex == 0) game.roundNumber + 1 else game.roundNumber
        gameDao.advanceTurn(gameId, nextTurnIndex, nextRoundNumber)
        return TurnPhase.ROLL_DICE
    }

    private fun finishGame(gameId: Int) {
        gameDao.updateStatus(gameId, GameStatus.FINISHED)
        playerDao.getPlayers(gameId).forEach { userDao.incrementGamesPlayed(it.id) }
        clearDBAfterGame(gameId)
    }

    private fun clearDBAfterGame(gameId: Int) {
        gameMarketplaceDao.deleteAllForGame(gameId)
        playerDao.getPlayers(gameId).forEach {
            playerCardDao.deleteAllByPlayerId(it.id)
            playerLandmarkDao.deleteAllByPlayerId(it.id)
        }
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
