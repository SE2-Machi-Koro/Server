package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.PlayerModel
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

    /**
     * Returns the winner in the given game, or null if nobody has won yet.
     *
     * This check is phase-agnostic: a winner is whoever has built all landmarks,
     * regardless of the game's current [TurnPhase]. It is typically invoked right
     * after transitioning to [TurnPhase.END_TURN], but may also be called from
     * other paths (e.g. a debug or mid-turn check) without restriction.
     */
    fun detectWinner(gameId: Int): PlayerModel? {
        gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game $gameId not found")

        return playerDao.getPlayers(gameId)
            .firstOrNull {
                hasPlayerWon(it.id)
            }
    }

}
