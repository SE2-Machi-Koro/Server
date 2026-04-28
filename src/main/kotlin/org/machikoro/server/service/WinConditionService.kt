package org.machikoro.server.service

import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.models.PlayerModel
import org.springframework.stereotype.Service

@Service
class WinConditionService(
    private val playerDao: PlayerDao,
    private val playerLandmarkDao: PlayerLandmarkDao,
    private val gameStateGuard: GameStateGuard
) {

    /** True iff the given player has built all landmarks. */
    fun hasPlayerWon(playerId: Int): Boolean =
        playerLandmarkDao.allBuilt(playerId)

    /** Returns the winner in the given game, or null if nobody has won yet. */
    fun detectWinner(gameId: Int): PlayerModel? {
        val game = gameStateGuard.ensureGameIsRunning(gameId)
        return playerDao.getPlayers(gameId)
            .firstOrNull {
                hasPlayerWon(it.id)
            }
    }

}
