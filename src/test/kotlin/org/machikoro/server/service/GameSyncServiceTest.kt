package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameMarketplaceModel
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerCardModel
import org.machikoro.server.domain.models.PlayerLandmarkModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.GameNotFoundException
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GameSyncServiceTest {

    private val gameDao = mock<GameDao>()
    private val playerDao = mock<PlayerDao>()
    private val playerCardDao = mock<PlayerCardDao>()
    private val playerLandmarkDao = mock<PlayerLandmarkDao>()
    private val gameMarketplaceDao = mock<GameMarketplaceDao>()
    private val service = GameSyncService(
        gameDao,
        playerDao,
        playerCardDao,
        playerLandmarkDao,
        gameMarketplaceDao,
    )

    private fun game(id: Int, status: GameStatus) = GameModel(
        id = id,
        status = status,
        hostUserId = 1,
        lobbyCode = "ABC1234",
        maxPlayers = 4,
        currentTurnIndex = 0,
        turnPhase = TurnPhase.ROLL_DICE,
        lastDiceRoll = null,
        roundNumber = 1,
        hasPurchasedThisTurn = false,
    )

    @Test
    fun `findActiveInProgressGameId delegates to PlayerDao`() {
        whenever(playerDao.findActiveGameIdByUserId(7)).thenReturn(99)

        assertEquals(99, service.findActiveInProgressGameId(7))

        verify(playerDao).findActiveGameIdByUserId(7)
    }

    @Test
    fun `findActiveInProgressGameId returns null when PlayerDao returns null`() {
        whenever(playerDao.findActiveGameIdByUserId(8)).thenReturn(null)

        assertNull(service.findActiveInProgressGameId(8))
        
        verify(playerDao).findActiveGameIdByUserId(8)
    }

    @Test
    fun `buildSnapshot returns players cards and sorted turnOrder`() {
        val gameId = 10
        val p1 = PlayerModel(id = 1, gameId = gameId, userId = 11, turnOrder = 1, coins = 3, lastSeenAt = null)
        val p2 = PlayerModel(id = 2, gameId = gameId, userId = 22, turnOrder = 0, coins = 3, lastSeenAt = null)

        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.IN_PROGRESS))
        whenever(playerDao.getPlayers(gameId)).thenReturn(listOf(p1, p2))
        // Bulk fetch: one query for all player IDs
        whenever(playerCardDao.findByPlayerIds(listOf(1, 2))).thenReturn(
            mapOf(
                1 to listOf(PlayerCardModel(1, CardType.BAKERY, 1)),
                // player 2 has no cards — absent from the map
            )
        )
        // Per-player landmark lookup — see GameSyncService.buildSnapshot for
        // the N=4 cap rationale on why this isn't a single batch query.
        whenever(playerLandmarkDao.findByPlayerId(1)).thenReturn(
            listOf(PlayerLandmarkModel(1, LandmarkType.TRAIN_STATION, isBuilt = true)),
        )
        whenever(playerLandmarkDao.findByPlayerId(2)).thenReturn(
            listOf(PlayerLandmarkModel(2, LandmarkType.TRAIN_STATION, isBuilt = false)),
        )
        whenever(gameMarketplaceDao.findByGameId(gameId)).thenReturn(
            listOf(
                GameMarketplaceModel(gameId, CardType.BAKERY, 5),
                GameMarketplaceModel(gameId, CardType.WHEAT_FIELD, 6),
            ),
        )

        val snapshot = service.buildSnapshot(gameId)

        assertEquals(gameId, snapshot.game.id)
        assertEquals(listOf(p1, p2), snapshot.players)
        assertEquals(listOf(2, 1), snapshot.turnOrder)
        assertEquals(1, snapshot.playerCards[1]?.size)
        assertTrue(snapshot.playerCards[2].isNullOrEmpty())
        // Landmarks are keyed by playerId and preserve isBuilt per row.
        assertEquals(1, snapshot.playerLandmarks[1]?.size)
        assertEquals(true, snapshot.playerLandmarks[1]?.first()?.isBuilt)
        assertEquals(1, snapshot.playerLandmarks[2]?.size)
        assertEquals(false, snapshot.playerLandmarks[2]?.first()?.isBuilt)
        // Marketplace flattens GameMarketplaceModel rows to cardType → quantity.
        assertEquals(mapOf(CardType.BAKERY to 5, CardType.WHEAT_FIELD to 6), snapshot.marketplace)
    }

    @Test
    fun `buildSnapshot works with zero players`() {
        val gameId = 11
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.IN_PROGRESS))
        whenever(playerDao.getPlayers(gameId)).thenReturn(emptyList())
        whenever(gameMarketplaceDao.findByGameId(gameId)).thenReturn(emptyList())

        val snapshot = service.buildSnapshot(gameId)

        assertEquals(gameId, snapshot.game.id)
        assertTrue(snapshot.players.isEmpty())
        assertTrue(snapshot.turnOrder.isEmpty())
        assertTrue(snapshot.playerCards.isEmpty())
        assertTrue(snapshot.playerLandmarks.isEmpty())
        assertTrue(snapshot.marketplace.isEmpty())
    }

    @Test
    fun `buildSnapshot throws when game is missing`() {
        whenever(gameDao.findById(404)).thenReturn(null)

        assertThrows<GameNotFoundException> {
            service.buildSnapshot(404)
        }
    }

    @Test
    fun `isInProgress returns true only for IN_PROGRESS games`() {
        whenever(gameDao.findById(1)).thenReturn(game(1, GameStatus.IN_PROGRESS))
        whenever(gameDao.findById(2)).thenReturn(game(2, GameStatus.WAITING))
        whenever(gameDao.findById(3)).thenReturn(null)

        assertTrue(service.isInProgress(1))
        assertFalse(service.isInProgress(2))
        assertFalse(service.isInProgress(3))
    }

    @Test
    fun `isUserInGame delegates to PlayerDao presence`() {
        whenever(playerDao.findByGameIdAndUserId(5, 50)).thenReturn(
            PlayerModel(id = 9, gameId = 5, userId = 50, turnOrder = 0, coins = 3, lastSeenAt = null)
        )
        whenever(playerDao.findByGameIdAndUserId(6, 50)).thenReturn(null)

        assertTrue(service.isUserInGame(50, 5))
        assertFalse(service.isUserInGame(50, 6))
    }
}