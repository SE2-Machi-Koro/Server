package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.models.PlayerModel
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.times
import org.mockito.kotlin.inOrder

class InitializationServiceTest {

    private val playerDao = mock<PlayerDao>()
    private val playerCardDao = mock<PlayerCardDao>()
    private val playerLandmarkDao = mock<PlayerLandmarkDao>()
    private val gameMarketplaceDao = mock<GameMarketplaceDao>()

    private val initializationService = InitializationService(
        playerDao = playerDao,
        playerCardDao = playerCardDao,
        playerLandmarkDao = playerLandmarkDao,
        gameMarketplaceDao = gameMarketplaceDao,
    )

    private val testPlayers = listOf(
        PlayerModel(id = 1, gameId = 1, userId = 101, turnOrder = 0, coins = 3, lastSeenAt = null),
        PlayerModel(id = 2, gameId = 1, userId = 102, turnOrder = 1, coins = 3, lastSeenAt = null),
        PlayerModel(id = 3, gameId = 1, userId = 103, turnOrder = 2, coins = 3, lastSeenAt = null),
        PlayerModel(id = 4, gameId = 1, userId = 104, turnOrder = 3, coins = 3, lastSeenAt = null),
    )

    // ==================== initializeGame() Tests ====================

    @Test
    fun `initializeGame should call all initialization methods`() {
        // Mock getPlayers to return test players
        org.mockito.kotlin.whenever(playerDao.getPlayers(1)).thenReturn(testPlayers)

        initializationService.initializeGame(gameId = 1)

        // Verify player order randomization was called (updateTurnOrder called 8 times: 4 temp + 4 final)
        verify(playerDao, times(8)).updateTurnOrder(org.mockito.kotlin.any(), org.mockito.kotlin.any())

        // Verify coins, landmarks, and cards were initialized
        verify(playerDao, times(4)).updateCoins(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        verify(playerLandmarkDao, times(4)).initForPlayer(org.mockito.kotlin.any())
        verify(playerCardDao, times(8)).upsert(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any()) // 4 players * 2 cards

        // Verify marketplace was initialized
        verify(gameMarketplaceDao).initForGame(1, 6)
    }

    @Test
    fun `initializeGame should return shuffled players`() {
        org.mockito.kotlin.whenever(playerDao.getPlayers(1)).thenReturn(testPlayers)

        val result = initializationService.initializeGame(gameId = 1)

        assertNotNull(result)
        assertEquals(4, result.size)
        assertTrue(result.all { it.gameId == 1 })
    }

    @Test
    fun `initializeGame should return players in different order than input`() {
        // The players will be shuffled, so the order should be different
        // (statistically 1 in 24 chance they're the same order, but acceptable for this test)
        org.mockito.kotlin.whenever(playerDao.getPlayers(1)).thenReturn(testPlayers)

        val result = initializationService.initializeGame(gameId = 1)

        // Verify result contains all players
        assertEquals(4, result.size)
        assertEquals(setOf(1, 2, 3, 4), result.map { it.id }.toSet())
    }

    // ==================== Random Order Generation Tests ====================

    @Test
    fun `generateRandomOrder should use two-pass update logic`() {
        org.mockito.kotlin.whenever(playerDao.getPlayers(1)).thenReturn(testPlayers)

        initializationService.initializeGame(gameId = 1)

        val updateTurnOrderCaptor = argumentCaptor<Int>()
        val updatePlayerIdCaptor = argumentCaptor<Int>()

        verify(playerDao, times(8)).updateTurnOrder(updatePlayerIdCaptor.capture(), updateTurnOrderCaptor.capture())

        val allTriples = updatePlayerIdCaptor.allValues.zip(updateTurnOrderCaptor.allValues)

        // First 4 calls should have TEMP_TURN_ORDER_OFFSET values (10000-10003)
        allTriples.take(4).forEach { (_, turnOrder) ->
            assertTrue(turnOrder in 10000..10003, "First pass should use temporary offset")
        }

        // Second 4 calls should have final turn orders (0-3)
        allTriples.drop(4).forEach { (_, turnOrder) ->
            assertTrue(turnOrder in 0..3, "Second pass should use final turn orders")
        }
    }

    // ==================== Coin Initialization Tests ====================

    @Test
    fun `initializePlayerCoins should set each player to 3 coins`() {
        org.mockito.kotlin.whenever(playerDao.getPlayers(1)).thenReturn(testPlayers)

        initializationService.initializeGame(gameId = 1)

        val coinCaptor = argumentCaptor<Int>()
        verify(playerDao, times(4)).updateCoins(org.mockito.kotlin.any(), coinCaptor.capture())

        // All captured coin values should be 3
        assertTrue(coinCaptor.allValues.all { it == 3 })
    }

    @Test
    fun `initializePlayerCoins should call updateCoins for each player`() {
        org.mockito.kotlin.whenever(playerDao.getPlayers(1)).thenReturn(testPlayers)

        initializationService.initializeGame(gameId = 1)

        val playerIdCaptor = argumentCaptor<Int>()
        verify(playerDao, times(4)).updateCoins(playerIdCaptor.capture(), org.mockito.kotlin.any())

        // All 4 players should be updated
        assertEquals(setOf(1, 2, 3, 4), playerIdCaptor.allValues.toSet())
    }

    // ==================== Landmark Initialization Tests ====================

    @Test
    fun `initializePlayerLandmarks should initialize landmarks for each player`() {
        org.mockito.kotlin.whenever(playerDao.getPlayers(1)).thenReturn(testPlayers)

        initializationService.initializeGame(gameId = 1)

        val playerIdCaptor = argumentCaptor<Int>()
        verify(playerLandmarkDao, times(4)).initForPlayer(playerIdCaptor.capture())

        // All 4 players should have landmarks initialized
        assertEquals(setOf(1, 2, 3, 4), playerIdCaptor.allValues.toSet())
    }

    // ==================== Card Initialization Tests ====================

    @Test
    fun `initializePlayerCards should give each player starting cards`() {
        org.mockito.kotlin.whenever(playerDao.getPlayers(1)).thenReturn(testPlayers)

        initializationService.initializeGame(gameId = 1)

        val playerIdCaptor = argumentCaptor<Int>()
        val cardTypeCaptor = argumentCaptor<CardType>()
        val quantityCaptor = argumentCaptor<Int>()

        verify(playerCardDao, times(8)).upsert(
            playerIdCaptor.capture(),
            cardTypeCaptor.capture(),
            quantityCaptor.capture()
        )

        // Each player gets 2 cards
        assertEquals(8, playerIdCaptor.allValues.size) // 4 players * 2 cards

        // Verify each player gets WHEAT_FIELD and BAKERY with quantity 1
        val cardsByPlayer = playerIdCaptor.allValues.zip(cardTypeCaptor.allValues).groupBy { (playerId, _) -> playerId }
        cardsByPlayer.forEach { (_, cards) ->
            assertEquals(2, cards.size)
            val cardTypes = cards.map { (_, cardType) -> cardType }.toSet()
            assertEquals(setOf(CardType.WHEAT_FIELD, CardType.BAKERY), cardTypes)
        }

        // All quantities should be 1
        assertTrue(quantityCaptor.allValues.all { it == 1 })
    }

    @Test
    fun `initializePlayerCards should distribute WHEAT_FIELD and BAKERY`() {
        org.mockito.kotlin.whenever(playerDao.getPlayers(1)).thenReturn(testPlayers)

        initializationService.initializeGame(gameId = 1)

        val cardTypeCaptor = argumentCaptor<CardType>()
        verify(playerCardDao, times(8)).upsert(org.mockito.kotlin.any(), cardTypeCaptor.capture(), org.mockito.kotlin.any())

        val cardTypes = cardTypeCaptor.allValues
        // Should have 4 WHEAT_FIELD (one per player) and 4 BAKERY (one per player)
        assertEquals(4, cardTypes.count { it == CardType.WHEAT_FIELD })
        assertEquals(4, cardTypes.count { it == CardType.BAKERY })
    }

    // ==================== Marketplace Initialization Tests ====================

    @Test
    fun `initializeMarketplace should initialize marketplace with 6 supply per card`() {
        org.mockito.kotlin.whenever(playerDao.getPlayers(1)).thenReturn(testPlayers)

        initializationService.initializeGame(gameId = 1)

        verify(gameMarketplaceDao).initForGame(1, 6)
    }

    @Test
    fun `initializeMarketplace should be called once per game`() {
        org.mockito.kotlin.whenever(playerDao.getPlayers(1)).thenReturn(testPlayers)

        initializationService.initializeGame(gameId = 1)

        verify(gameMarketplaceDao, times(1)).initForGame(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    // ==================== Edge Cases ====================

    @Test
    fun `initializeGame should handle single player game`() {
        val singlePlayer = listOf(
            PlayerModel(id = 1, gameId = 2, userId = 101, turnOrder = 0, coins = 3, lastSeenAt = null)
        )
        org.mockito.kotlin.whenever(playerDao.getPlayers(2)).thenReturn(singlePlayer)

        val result = initializationService.initializeGame(gameId = 2)

        assertEquals(1, result.size)
        assertEquals(101, result[0].userId)
    }

    @Test
    fun `initializeGame should handle maximum player count (4 players)`() {
        org.mockito.kotlin.whenever(playerDao.getPlayers(1)).thenReturn(testPlayers)

        val result = initializationService.initializeGame(gameId = 1)

        assertEquals(4, result.size)
    }

    @Test
    fun `initializeGame should process initialization in correct order`() {
        org.mockito.kotlin.whenever(playerDao.getPlayers(1)).thenReturn(testPlayers)

        initializationService.initializeGame(gameId = 1)

        // Use inOrder to verify call sequence
        val inOrder = inOrder(playerDao, playerLandmarkDao, playerCardDao, gameMarketplaceDao)

        // Verify order: updateTurnOrder, updateCoins, then initForPlayer, then upsert, then initForGame
        inOrder.verify(playerDao, times(8)).updateTurnOrder(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        inOrder.verify(playerDao, times(4)).updateCoins(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        inOrder.verify(playerLandmarkDao, times(4)).initForPlayer(org.mockito.kotlin.any())
        inOrder.verify(playerCardDao, times(8)).upsert(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        inOrder.verify(gameMarketplaceDao).initForGame(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }
}