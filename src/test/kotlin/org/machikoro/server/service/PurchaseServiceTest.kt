package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.LandmarkDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.CardModel
import org.machikoro.server.domain.models.GameMarketplaceModel
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.LandmarkModel
import org.machikoro.server.domain.models.PlayerLandmarkModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.PurchaseType
import org.machikoro.server.exception.CustomWebSocketException
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PurchaseServiceTest {

    private val gameDao = mock<GameDao>()
    private val playerDao = mock<PlayerDao>()
    private val cardDao = mock<CardDao>()
    private val landmarkDao = mock<LandmarkDao>()
    private val gameMarketplaceDao = mock<GameMarketplaceDao>()
    private val playerCardDao = mock<PlayerCardDao>()
    private val playerLandmarkDao = mock<PlayerLandmarkDao>()
    private val gameStateGuard = mock<GameStateGuard>()

    private val service = PurchaseService(
        gameDao = gameDao,
        playerDao = playerDao,
        cardDao = cardDao,
        landmarkDao = landmarkDao,
        gameMarketplaceDao = gameMarketplaceDao,
        playerCardDao = playerCardDao,
        playerLandmarkDao = playerLandmarkDao,
        gameStateGuard = gameStateGuard,
    )

    private fun activeGame(
        gameId: Int,
        hasPurchasedThisTurn: Boolean = false,
        turnPhase: TurnPhase = TurnPhase.BUY_OR_BUILD,
    ) = GameModel(
        id = gameId,
        status = GameStatus.IN_PROGRESS,
        hostUserId = 1,
        currentTurnIndex = 0,
        turnPhase = turnPhase,
        lastDiceRoll = 4,
        roundNumber = 1,
        hasPurchasedThisTurn = hasPurchasedThisTurn,
    )

    private fun player(gameId: Int, coins: Int = 5) = PlayerModel(
        id = 10,
        gameId = gameId,
        userId = 1,
        turnOrder = 0,
        coins = coins,
    )

    @Test
    fun `purchase establishment deducts coins updates ownership and supply`() {
        val gameId = 1
        whenever(gameStateGuard.ensureGameIsRunning(gameId)).thenReturn(activeGame(gameId))
        whenever(playerDao.findByGameId(gameId)).thenReturn(listOf(player(gameId, coins = 5)))
        whenever(cardDao.findByCardType(CardType.BAKERY)).thenReturn(
            CardModel(1, CardType.BAKERY, "Bakery", 1, 2, 3, 1)
        )
        whenever(gameMarketplaceDao.findByGameIdAndType(gameId, CardType.BAKERY)).thenReturn(
            GameMarketplaceModel(gameId, CardType.BAKERY, 6)
        )
        whenever(playerCardDao.findByPlayerId(10)).thenReturn(emptyList())

        val result = service.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)

        assertEquals(PurchaseType.ESTABLISHMENT, result.purchaseType)
        assertEquals(CardType.BAKERY, result.cardType)
        assertEquals(TurnPhase.BUY_OR_BUILD, result.turnPhase)

        verify(gameMarketplaceDao).initForGame(gameId)
        verify(playerDao).updateCoins(10, 4)
        verify(playerCardDao).upsert(10, CardType.BAKERY, 1)
        verify(gameMarketplaceDao).decrementQuantity(gameId, CardType.BAKERY)
        verify(gameDao).updateHasPurchasedThisTurn(gameId, true)
    }

    @Test
    fun `purchase landmark deducts coins and marks landmark built`() {
        val gameId = 2
        whenever(gameStateGuard.ensureGameIsRunning(gameId)).thenReturn(activeGame(gameId))
        whenever(playerDao.findByGameId(gameId)).thenReturn(listOf(player(gameId, coins = 6)))
        whenever(landmarkDao.findByLandmarkType(LandmarkType.TRAIN_STATION)).thenReturn(
            LandmarkModel(1, LandmarkType.TRAIN_STATION, "Train Station", 4)
        )
        whenever(playerLandmarkDao.findByPlayerIdAndType(10, LandmarkType.TRAIN_STATION)).thenReturn(
            PlayerLandmarkModel(10, LandmarkType.TRAIN_STATION, false)
        )

        val result = service.purchase(gameId, PurchaseType.LANDMARK, null, LandmarkType.TRAIN_STATION)

        assertEquals(PurchaseType.LANDMARK, result.purchaseType)
        assertEquals(LandmarkType.TRAIN_STATION, result.landmarkType)

        verify(playerLandmarkDao).initForPlayer(10)
        verify(playerDao).updateCoins(10, 2)
        verify(playerLandmarkDao).markBuilt(10, LandmarkType.TRAIN_STATION)
        verify(gameDao).updateHasPurchasedThisTurn(gameId, true)
    }

    @Test
    fun `purchase rejects turns outside buy or build`() {
        val gameId = 3
        whenever(gameStateGuard.ensureGameIsRunning(gameId)).thenReturn(
            activeGame(gameId, turnPhase = TurnPhase.RESOLVE_EFFECTS)
        )

        val ex = assertThrows<CustomWebSocketException> {
            service.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)
        }

        assertEquals("INVALID_TURN_PHASE", ex.errorCode)
        verify(playerDao, never()).findByGameId(any())
    }

    @Test
    fun `purchase rejects repeated purchases in same turn`() {
        val gameId = 4
        whenever(gameStateGuard.ensureGameIsRunning(gameId)).thenReturn(
            activeGame(gameId, hasPurchasedThisTurn = true)
        )

        val ex = assertThrows<CustomWebSocketException> {
            service.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)
        }

        assertEquals("PURCHASE_ALREADY_MADE", ex.errorCode)
        verify(playerDao, never()).findByGameId(any())
    }

    @Test
    fun `purchase rejects establishment when coins are insufficient`() {
        val gameId = 5
        whenever(gameStateGuard.ensureGameIsRunning(gameId)).thenReturn(activeGame(gameId))
        whenever(playerDao.findByGameId(gameId)).thenReturn(listOf(player(gameId, coins = 1)))
        whenever(cardDao.findByCardType(CardType.STADIUM)).thenReturn(
            CardModel(1, CardType.STADIUM, "Stadium", 6, 6, 6, 2)
        )

        val ex = assertThrows<CustomWebSocketException> {
            service.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.STADIUM, null)
        }

        assertEquals("INSUFFICIENT_COINS", ex.errorCode)
        verify(gameMarketplaceDao, never()).initForGame(gameId)
    }

    @Test
    fun `purchase rejects establishment when supply is unavailable`() {
        val gameId = 6
        whenever(gameStateGuard.ensureGameIsRunning(gameId)).thenReturn(activeGame(gameId))
        whenever(playerDao.findByGameId(gameId)).thenReturn(listOf(player(gameId)))
        whenever(cardDao.findByCardType(CardType.BAKERY)).thenReturn(
            CardModel(1, CardType.BAKERY, "Bakery", 1, 2, 3, 1)
        )
        whenever(gameMarketplaceDao.findByGameIdAndType(gameId, CardType.BAKERY)).thenReturn(
            GameMarketplaceModel(gameId, CardType.BAKERY, 0)
        )

        val ex = assertThrows<CustomWebSocketException> {
            service.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)
        }

        assertEquals("CARD_UNAVAILABLE", ex.errorCode)
        verify(playerDao, never()).updateCoins(any(), any())
    }

    @Test
    fun `purchase rejects landmark that is already built`() {
        val gameId = 7
        whenever(gameStateGuard.ensureGameIsRunning(gameId)).thenReturn(activeGame(gameId))
        whenever(playerDao.findByGameId(gameId)).thenReturn(listOf(player(gameId, coins = 6)))
        whenever(landmarkDao.findByLandmarkType(LandmarkType.TRAIN_STATION)).thenReturn(
            LandmarkModel(1, LandmarkType.TRAIN_STATION, "Train Station", 4)
        )
        whenever(playerLandmarkDao.findByPlayerIdAndType(10, LandmarkType.TRAIN_STATION)).thenReturn(
            PlayerLandmarkModel(10, LandmarkType.TRAIN_STATION, true)
        )

        val ex = assertThrows<CustomWebSocketException> {
            service.purchase(gameId, PurchaseType.LANDMARK, null, LandmarkType.TRAIN_STATION)
        }

        assertEquals("LANDMARK_ALREADY_BUILT", ex.errorCode)
        verify(playerDao, never()).updateCoins(any(), any())
    }
}
