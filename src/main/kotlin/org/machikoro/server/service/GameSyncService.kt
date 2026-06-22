package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.LandmarkDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.dto.CardDefinitionDto
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.ClientScreen
import org.machikoro.server.dto.LandmarkDefinitionDto
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

    /**
     * Card and landmark definitions are static reference data — they never change
     * between games.  Caching them here avoids re-querying (and re-allocating) the
     * same lists on every [buildSnapshot] call.  Without this cache, a burst of
     * reconnects (e.g. after a server restart) would issue O(n) identical DB reads
     * and allocate a fresh list of ~15 CardDefinitionDto objects per call, adding
     * measurable GC pressure under the 1 GB heap limit.
     */
    private val cachedCardDefinitions: List<CardDefinitionDto> by lazy {
        cardDao.findAll().map { it.toDefinitionDto() }
    }

    private val cachedLandmarkDefinitions: List<LandmarkDefinitionDto> by lazy {
        landmarkDao.findAll().map { it.toDefinitionDto() }
    }

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
        // PlayerLandmarkDao has no batch lookup today; with maxPlayers = 4 the
        // per-player iteration here is bounded at 4 queries per snapshot. If
        // that ever matters, add findByPlayerIds() mirroring PlayerCardDao.
        val playerLandmarks = players.associate { player ->
            player.id to playerLandmarkDao.findByPlayerId(player.id)
        }
        val marketplace = gameMarketplaceDao.findByGameIdAsMap(gameId)
        val cardDefinitions = cachedCardDefinitions
        val landmarkDefinitions = cachedLandmarkDefinitions
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
