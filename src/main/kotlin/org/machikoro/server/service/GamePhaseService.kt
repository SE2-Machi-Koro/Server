package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.dto.EndTurnOutcome
import org.machikoro.server.exception.CustomWebSocketException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GamePhaseService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val userDao: UserDao,
    private val playerCardDao: PlayerCardDao,
    private val playerLandmarkDao: PlayerLandmarkDao,
    private val gameMarketplaceDao: GameMarketplaceDao,
    private val gameStateGuard: GameStateGuard,
    private val winConditionService: WinConditionService,
    private val gameTransactionRunner: GameTransactionRunner,
) {

    /**
     * Ends the active player's buy-or-build window, checks for a winner, and
     * either finishes the game or starts the next turn.
     */
    fun endTurn(gameId: Int): EndTurnOutcome = gameTransactionRunner.inTransaction {
        val game = gameStateGuard.ensureGameIsRunning(gameId)
        if (game.turnPhase != TurnPhase.BUY_OR_BUILD) {
            throw CustomWebSocketException(
                "INVALID_TURN_PHASE",
                "Turn can only be ended during BUY_OR_BUILD",
            )
        }

        if (!gameDao.tryTransitionPhase(gameId, TurnPhase.BUY_OR_BUILD, TurnPhase.END_TURN)) {
            throw CustomWebSocketException("TURN_ALREADY_ENDED", "This turn has already ended")
        }

        winConditionService.detectWinner(gameId)?.let { winner ->
            userDao.incrementWins(winner.userId)
            playerDao.getPlayers(gameId).forEach { userDao.incrementGamesPlayed(it.userId) }
            gameDao.updateStatus(gameId, GameStatus.FINISHED)
            return@inTransaction EndTurnOutcome.Won(winner.id, game.roundNumber)
        }

        val nextPhase = advanceTurn(gameId)
        EndTurnOutcome.Continue(nextPhase)
    }

    /** Advances a game to the next player's turn and persists it. */
    private fun advanceTurn(gameId: Int): TurnPhase {
        val game = gameStateGuard.ensureGameIsRunning(gameId)
        val players = playerDao.getPlayers(gameId)
        check(players.isNotEmpty()) { "Game $gameId has no players" }

        val nextTurnIndex = (game.currentTurnIndex + 1) % players.size
        val nextRoundNumber = if (nextTurnIndex == 0) game.roundNumber + 1 else game.roundNumber

        gameDao.advanceTurn(gameId, nextTurnIndex, nextRoundNumber)
        return TurnPhase.ROLL_DICE
    }

    /**
     * Runs as transaction so game cleanup stays
     * atomic when a landmark purchase produces a winner.
     */
    @Transactional
     fun cleanupFinishedGameData(gameId: Int) {
        gameStateGuard.ensureGameIsFinished(gameId)

        gameMarketplaceDao.deleteAllForGame(gameId)

        playerDao.getPlayers(gameId).forEach {
            playerCardDao.deleteAllByPlayerId(it.id)
            playerLandmarkDao.deleteAllByPlayerId(it.id)
        }
        playerDao.deleteByGameId(gameId)

        gameDao.delete(gameId)
    }
}
