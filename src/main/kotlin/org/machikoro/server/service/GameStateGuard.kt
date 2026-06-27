package org.machikoro.server.service

import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameNotFoundException
import org.springframework.stereotype.Service

@Service
class GameStateGuard(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
) {

    /**
     * Loads the given game and ensures it is IN_PROGRESS. Returns the loaded
     * [GameModel] so callers can avoid a second DAO round-trip.
     *
     * Throws [CustomWebSocketException] with:
     *  - `GAME_FINISHED`     — game has already ended.
     *  - `GAME_NOT_STARTED`  — game is still in the lobby (WAITING).
     * Throws [GameNotFoundException] if the id is unknown.
     * Intended to be called at the top of any gameplay action.
     */
    fun ensureGameIsRunning(gameId: Int): GameModel {
        val game = gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game $gameId not found")
        // Only IN_PROGRESS games may receive gameplay actions
        when (game.status) {
            GameStatus.FINISHED -> throw CustomWebSocketException(
                errorCode = "GAME_FINISHED",
                message = "Game $gameId has already ended",
            )
            GameStatus.WAITING -> throw CustomWebSocketException(
                errorCode = "GAME_NOT_STARTED",
                message = "Game $gameId has not started yet",
            )
            GameStatus.IN_PROGRESS -> return game
        }
    }

    fun ensureGameIsFinished(gameId: Int) {
        val game = gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game $gameId not found")

        if (game.status != GameStatus.FINISHED) {
            throw CustomWebSocketException(
                errorCode = "GAME_RUNNING",
                message = "Game $gameId has not ended yet"
            )
        }
    }

    /**
     * Verifies that [user] is the player whose turn is currently active in
     * [gameId]. Used by all turn-bound mutations (purchase, endTurn, rollDice,
     * advancePhase, resolveEffects) so client payloads cannot impersonate
     * another player by forging gameId or playerId fields.
     *
     * Callers resolve the principal via
     * `SimpMessageHeaderAccessor.requireUserPrincipal()` before calling — the
     * UNAUTHENTICATED case is handled at that boundary, not here.
     *
     * Throws [CustomWebSocketException] with one of:
     *  - `GAME_FINISHED`     — game has already ended (via [ensureGameIsRunning]).
     *  - `GAME_NOT_STARTED`  — game is still in the lobby (via [ensureGameIsRunning]).
     *  - `NO_ACTIVE_PLAYER`  — currentTurnIndex points outside the player list.
     *  - `NOT_YOUR_TURN`     — caller is authenticated but not the active player.
     */
    fun ensureSenderIsActivePlayer(gameId: Int, user: UserPrincipal): PlayerModel {
        val game = ensureGameIsRunning(gameId)
        val active = playerDao.getPlayers(gameId).getOrNull(game.currentTurnIndex)
            ?: throw CustomWebSocketException(
                errorCode = "NO_ACTIVE_PLAYER",
                message = "Game $gameId has no player at turn index ${game.currentTurnIndex}",
            )
        if (active.userId != user.userId) {
            throw CustomWebSocketException(
                errorCode = "NOT_YOUR_TURN",
                message = "It is not your turn",
            )
        }
        return active
    }

}
