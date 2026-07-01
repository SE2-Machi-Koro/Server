package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.LandmarkDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.ClientScreen
import org.machikoro.server.dto.SessionStateDto
import org.machikoro.server.dto.toDefinitionDto
import org.machikoro.server.exception.GameNotFoundException
import org.springframework.stereotype.Service

@Service
class GameSyncService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val playerCardDao: PlayerCardDao,
    private val playerLandmarkDao: PlayerLandmarkDao,
    private val gameMarketplaceDao: GameMarketplaceDao,
    private val cardDao: CardDao,
    private val landmarkDao: LandmarkDao,
) {

    fun findActiveInProgressGameId(userId: Int): Int? =
        playerDao.findActiveGameIdByUserId(userId)

    fun resolveSessionState(userId: Int): SessionStateDto {
        val membership = playerDao.findCurrentMembershipByUserId(userId)
            ?: return SessionStateDto(screen = ClientScreen.MAIN)

        val screen = when (membership.game.status) {
            GameStatus.WAITING -> ClientScreen.LOBBY
            GameStatus.IN_PROGRESS -> ClientScreen.GAME
            GameStatus.FINISHED -> ClientScreen.MAIN
        }

        return SessionStateDto(
            screen = screen,
            gameId = membership.game.id,
            playerId = membership.player.id,
            lobbyCode = membership.game.lobbyCode,
            gameStatus = membership.game.status,
            turnPhase = membership.game.turnPhase,
        )
    }

    fun buildSnapshot(gameId: Int): GameStateDto {
        val game = gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game $gameId not found")

        val players = playerDao.getPlayers(gameId)
        // Fetch all player cards in one query instead of N individual lookups.
        val playerCardsByPlayerId = playerCardDao.findByPlayerIds(players.map { it.id })
        val playerCards = players.associate { player ->
            player.id to (playerCardsByPlayerId[player.id] ?: emptyList())
        }
        val playerLandmarksByPlayerId = playerLandmarkDao.findByPlayerIds(players.map { it.id })
        val playerLandmarks = players.associate { player ->
            player.id to (playerLandmarksByPlayerId[player.id] ?: emptyList())
        }
        val marketplace = gameMarketplaceDao.findByGameIdAsMap(gameId)
        val cardDefinitions = cardDao.findAll().map { it.toDefinitionDto() }
        val landmarkDefinitions = landmarkDao.findAll().map { it.toDefinitionDto() }
        val playerUsernames = playerDao.getLobbyRoster(gameId).associate { it.playerId to it.username }

        val sortedPlayers = players.sortedBy { it.turnOrder }
        return GameStateDto(
            game = game,
            players = players,
            playerCards = playerCards,
            playerLandmarks = playerLandmarks,
            marketplace = marketplace,
            cardDefinitions = cardDefinitions,
            landmarkDefinitions = landmarkDefinitions,
            turnOrder = sortedPlayers.map { it.userId },
            activePlayerId = sortedPlayers.getOrNull(game.currentTurnIndex)?.userId,
            playerUsernames = playerUsernames,
        )
    }

    fun isInProgress(gameId: Int): Boolean =
        gameDao.findById(gameId)?.status == GameStatus.IN_PROGRESS

    fun isUserInGame(userId: Int, gameId: Int): Boolean =
        playerDao.findByGameIdAndUserId(gameId, userId) != null
}
