package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.exception.GameNotFoundException
import org.springframework.stereotype.Service

@Service
class GameSyncService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val playerCardDao: PlayerCardDao,
    private val playerLandmarkDao: PlayerLandmarkDao,
    private val gameMarketplaceDao: GameMarketplaceDao,
) {

    fun findActiveInProgressGameId(userId: Int): Int? =
        playerDao.findActiveGameIdByUserId(userId)

    fun buildSnapshot(gameId: Int): GameStateDto {
        val game = gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game $gameId not found")

        val players = playerDao.getPlayers(gameId)
        // Fetch all player cards in one query instead of N individual lookups.
        val playerCardsByPlayerId = playerCardDao.findByPlayerIds(players.map { it.id })
        val playerCards = players.associate { player ->
            player.id to (playerCardsByPlayerId[player.id] ?: emptyList())
        }
        // PlayerLandmarkDao has no batch lookup today; with maxPlayers = 4 the
        // per-player iteration here is bounded at 4 queries per snapshot. If
        // that ever matters, add findByPlayerIds() mirroring PlayerCardDao.
        val playerLandmarks = players.associate { player ->
            player.id to playerLandmarkDao.findByPlayerId(player.id)
        }
        val marketplace = gameMarketplaceDao.findByGameIdAsMap(gameId)

        return GameStateDto(
            game = game,
            players = players,
            playerCards = playerCards,
            playerLandmarks = playerLandmarks,
            marketplace = marketplace,
            turnOrder = players.sortedBy { it.turnOrder }.map { it.id },
        )
    }

    fun isInProgress(gameId: Int): Boolean =
        gameDao.findById(gameId)?.status == GameStatus.IN_PROGRESS

    fun isUserInGame(userId: Int, gameId: Int): Boolean =
        playerDao.findByGameIdAndUserId(gameId, userId) != null
}

