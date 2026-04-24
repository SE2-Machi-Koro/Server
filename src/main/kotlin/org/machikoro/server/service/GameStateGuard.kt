package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameNotFoundException
import org.springframework.stereotype.Service

@Service
class GameStateGuard(
    private val gameDao: GameDao,
) {

    /**
     * Ensures the given game is still active. Throws [CustomWebSocketException] with
     * errorCode `GAME_FINISHED` if the game has already ended, or [GameNotFoundException]
     * if the id is unknown. Intended to be called at the top of any gameplay action.
     */
    fun ensureGameIsRunning(gameId: Int) {
        val game = gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game $gameId not found")
        if (game.status == GameStatus.FINISHED) {
            throw CustomWebSocketException(
                errorCode = "GAME_FINISHED",
                message = "Game $gameId has already ended",
            )
        }
    }
}
