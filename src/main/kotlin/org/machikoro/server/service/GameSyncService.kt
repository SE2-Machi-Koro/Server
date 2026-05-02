package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.exception.GameNotFoundException
import org.springframework.stereotype.Service

@Service
class GameSyncService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val playerCardDao: PlayerCardDao,
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

        return GameStateDto(
            game = game,
            players = players,
            playerCards = playerCards,
            turnOrder = players.sortedBy { it.turnOrder }.map { it.id },
        )
    }

    fun isInProgress(gameId: Int): Boolean =
        gameDao.findById(gameId)?.status == GameStatus.IN_PROGRESS

    fun isUserInGame(userId: Int, gameId: Int): Boolean =
        playerDao.findByGameIdAndUserId(gameId, userId) != null
}

