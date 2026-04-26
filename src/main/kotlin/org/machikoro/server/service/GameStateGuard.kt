package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameNotFoundException
import org.springframework.stereotype.Service

@Service
class GameStateGuard(
    private val gameDao: GameDao,
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

    fun ensureGameIsFinished(gameId: Int): GameModel {
        val game = gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game $gameId not found")
        if (game.status == GameStatus.FINISHED) {
            return game
        } else  throw CustomWebSocketException(
            errorCode = "GAME_RUNNING",
            message = "Game $gameId has not ended yet")
    }

}
