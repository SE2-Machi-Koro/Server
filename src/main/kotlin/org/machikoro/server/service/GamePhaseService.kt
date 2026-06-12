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
            return@inTransaction finishGameWithWinner(gameId, winner, game.roundNumber)
        }

        val nextPhase = advanceTurn(gameId)
        EndTurnOutcome.Continue(nextPhase)
    }

    /**
     * Applies the shared end-of-game win side effects — records the win and
     * games-played stats and transitions the game to FINISHED — and returns the
     * [EndTurnOutcome.Won]. Reused by the natural [endTurn] win path and the
     * debug end-game endpoint (#305) so both paths produce an identical result.
     * Callers run inside their own transaction and own the GAME_END broadcast.
     */
    fun finishGameWithWinner(gameId: Int, winner: PlayerModel, roundNumber: Int): EndTurnOutcome.Won {
        userDao.incrementWins(winner.userId)
        playerDao.getPlayers(gameId).forEach { userDao.incrementGamesPlayed(it.userId) }
        gameDao.updateStatus(gameId, GameStatus.FINISHED)
        return EndTurnOutcome.Won(winner.id, roundNumber)
    }

    /** Advances a game to the next player's turn and persists it. */
    private fun advanceTurn(gameId: Int): TurnPhase {
        val game = gameStateGuard.ensureGameIsRunning(gameId)
        val players = playerDao.getPlayers(gameId)
        check(players.isNotEmpty()) { "Game $gameId has no players" }

        val currentPlayer = players[game.currentTurnIndex]
        val consumeExtra = (game.extraTurnPlayerId != null &&
                game.extraTurnPlayerId == currentPlayer.id &&
                game.extraTurnRoundNumber == game.roundNumber)

        val nextTurnIndex = if (consumeExtra){ game.currentTurnIndex }
        else { (game.currentTurnIndex + 1) % players.size }

        val nextRoundNumber = if (!consumeExtra && nextTurnIndex == 0) game.roundNumber + 1 else game.roundNumber

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
