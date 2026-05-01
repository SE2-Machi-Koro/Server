package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameNotFoundException
import org.springframework.stereotype.Service

@Service
class WinConditionService(
    private val playerDao: PlayerDao,
    private val playerLandmarkDao: PlayerLandmarkDao,
    private val gameDao: GameDao
) {

    /** True iff the given player has built all landmarks. */
    private fun hasPlayerWon(playerId: Int): Boolean =
        playerLandmarkDao.allBuilt(playerId)

    /** Returns the winner in the given game, or null if nobody has won yet. */
    fun detectWinner(gameId: Int): PlayerModel? {
        val game = gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game $gameId not found")

        if (game.turnPhase != TurnPhase.END_TURN) {
            throw CustomWebSocketException(
                errorCode = "NOT_END_TURN_PHASE",
                message = "Game ${game.id} must be in END_TURN State to determine a winner",
            )
        }

        return playerDao.getPlayers(gameId)
            .firstOrNull {
                hasPlayerWon(it.id)
            }
    }

}
