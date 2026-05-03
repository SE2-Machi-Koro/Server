package org.machikoro.server.service

import java.security.Principal
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameNotFoundException
import org.springframework.stereotype.Service

@Service
class GameStateGuard(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
) {

    /**
     * Loads the given game and ensures it is still active. Returns the loaded
     * [GameModel] so callers can avoid a second DAO round-trip.
     *
     * Throws [CustomWebSocketException] with errorCode `GAME_FINISHED` if the
     * game has already ended, or [GameNotFoundException] if the id is unknown.
     * Intended to be called at the top of any gameplay action.
     */
    fun ensureGameIsRunning(gameId: Int): GameModel {
        val game = gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game $gameId not found")
        if (game.status == GameStatus.FINISHED) {
            throw CustomWebSocketException(
                errorCode = "GAME_FINISHED",
                message = "Game $gameId has already ended",
            )
        }
        return game
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
     * Verifies that [principal] is the player whose turn is currently active in
     * [gameId]. Used by all turn-bound mutations (purchase, endTurn, rollDice,
     * advancePhase, resolveEffects) so client payloads cannot impersonate
     * another player by forging gameId or playerId fields.
     *
     * Returns the resolved [UserPrincipal] for callers that want it.
     *
     * Throws [CustomWebSocketException] with one of:
     *  - `UNAUTHENTICATED`   — no [UserPrincipal] is attached to the session.
     *  - `GAME_FINISHED`     — game has already ended (via [ensureGameIsRunning]).
     *  - `NO_ACTIVE_PLAYER`  — currentTurnIndex points outside the player list.
     *  - `NOT_YOUR_TURN`     — caller is authenticated but not the active player.
     */
    fun ensureSenderIsActivePlayer(gameId: Int, principal: Principal?): UserPrincipal {
        val user = requireUserPrincipal(principal)
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
        return user
    }

    /**
     * Verifies that [principal] owns the player identified by [playerId] within
     * [gameId]. Used for `/game.leave` so a user can only remove their own seat
     * from a finished game, not another player's.
     *
     * Throws [CustomWebSocketException] with one of:
     *  - `UNAUTHENTICATED`     — no [UserPrincipal] is attached to the session.
     *  - `PLAYER_NOT_IN_GAME`  — [playerId] does not exist in [gameId].
     *  - `NOT_YOUR_PLAYER`     — caller is authenticated but does not own the player.
     */
    fun ensureSenderOwnsPlayer(gameId: Int, playerId: Int, principal: Principal?): UserPrincipal {
        val user = requireUserPrincipal(principal)
        val player = playerDao.getPlayers(gameId).firstOrNull { it.id == playerId }
            ?: throw CustomWebSocketException(
                errorCode = "PLAYER_NOT_IN_GAME",
                message = "Player $playerId is not in game $gameId",
            )
        if (player.userId != user.userId) {
            throw CustomWebSocketException(
                errorCode = "NOT_YOUR_PLAYER",
                message = "You do not own player $playerId",
            )
        }
        return user
    }

    private fun requireUserPrincipal(principal: Principal?): UserPrincipal =
        principal as? UserPrincipal ?: throw CustomWebSocketException(
            errorCode = "UNAUTHENTICATED",
            message = "Authenticated principal required",
        )
}
