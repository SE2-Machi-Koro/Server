package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.*
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.CardColor
import org.machikoro.server.domain.enums.PaymentSource
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.CardModel
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerCardModel
import org.machikoro.server.domain.models.PlayerModel
import org.mockito.kotlin.whenever
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.models.PlayerLandmarkModel
import org.machikoro.server.exception.CustomWebSocketException
import org.mockito.kotlin.any

class EarningsServiceImplTest {

    private lateinit var playerDao: PlayerDao
    private lateinit var playerCardDao: PlayerCardDao
    private lateinit var cardDao: CardDao
    private lateinit var gameDao: GameDao
    private lateinit var gameStateGuard: GameStateGuard
    private lateinit var playerLandmarkDao: PlayerLandmarkDao
    private val transactionRunner = object : GameTransactionRunner {
        override fun <T> inTransaction(action: () -> T): T = action()
    }

    private lateinit var service: EarningsServiceImpl

    @BeforeEach
    fun setup() {
        playerDao = mock(PlayerDao::class.java)
        playerCardDao = mock(PlayerCardDao::class.java)
        cardDao = mock(CardDao::class.java)
        gameDao = mock(GameDao::class.java)
        gameStateGuard = mock(GameStateGuard::class.java)
        playerLandmarkDao = mock(PlayerLandmarkDao::class.java)

        service = EarningsServiceImpl(
            playerDao,
            playerCardDao,
            cardDao,
            gameDao,
            gameStateGuard,
            transactionRunner,
            playerLandmarkDao,
        )
    }

    // Basic calculation helper tests

    @Test
    fun `zero cards returns zero`() {
        assertEquals(0, service.computeEarnings(emptyList()))
    }

    @Test
    fun `single card returns quantity times income`() {
        assertEquals(6, service.computeEarnings(listOf(2 to 3)))
    }

    @Test
    fun `multiple card types sums correctly`() {
        assertEquals(11, service.computeEarnings(listOf(2 to 3, 1 to 5)))
    }

    @Test
    fun `multiple quantities sums correctly`() {
        assertEquals(20, service.computeEarnings(listOf(3 to 4, 2 to 4)))
    }

    // Everyone should get blue income

    @Test
    fun `blue cards give coins to all players`() {
        val players = listOf(
            player(1, 3),
            player(2, 5)
        )

        val wheatField = card(
            cardType = CardType.WHEAT_FIELD,
            color = CardColor.BLUE,
            income = 1
        )

        whenever(cardDao.findByActivationNumber(1))
            .thenReturn(listOf(wheatField))

        whenever(playerDao.getPlayers(1))
            .thenReturn(players)

        whenever(playerCardDao.findByPlayerId(1))
            .thenReturn(listOf(playerCard(CardType.WHEAT_FIELD, 2)))

        whenever(playerCardDao.findByPlayerId(2))
            .thenReturn(listOf(playerCard(CardType.WHEAT_FIELD, 1)))

        val deltas = service.processEarnings(1, 1, 1)

        verify(playerDao).updateCoins(1, 5)
        verify(playerDao).updateCoins(2, 6)
        // Signed per-player coin deltas drive the client coin sounds (#389).
        assertEquals(mapOf(1 to 2, 2 to 1), deltas)
    }

    // Only the current player should earn green income

    @Test
    fun `green cards only reward active player`() {
        val players = listOf(
            player(1, 3),
            player(2, 3)
        )

        val bakery = card(
            cardType = CardType.BAKERY,
            color = CardColor.GREEN,
            income = 2
        )

        whenever(cardDao.findByActivationNumber(2))
            .thenReturn(listOf(bakery))

        whenever(playerDao.getPlayers(1))
            .thenReturn(players)

        whenever(playerCardDao.findByPlayerId(1))
            .thenReturn(listOf(playerCard(CardType.BAKERY, 1)))

        whenever(playerCardDao.findByPlayerId(2))
            .thenReturn(listOf(playerCard(CardType.BAKERY, 1)))

        service.processEarnings(1, 2, 1)

        verify(playerDao).updateCoins(1, 5)
        verify(playerDao, never()).updateCoins(2, 5)
    }

    // Red cards steal from the active player

    @Test
    fun `red cards steal from active player`() {
        val players = listOf(
            player(1, 5),
            player(2, 1)
        )

        val cafe = card(
            cardType = CardType.CAFE,
            color = CardColor.RED,
            income = 2
        )

        whenever(cardDao.findByActivationNumber(3))
            .thenReturn(listOf(cafe))

        whenever(playerDao.getPlayers(1))
            .thenReturn(players)

        whenever(playerCardDao.findByPlayerId(1))
            .thenReturn(emptyList())

        whenever(playerCardDao.findByPlayerId(2))
            .thenReturn(listOf(playerCard(CardType.CAFE, 1)))

        service.processEarnings(1, 3, 1)

        verify(playerDao).updateCoins(1, 3)
        verify(playerDao).updateCoins(2, 3)
    }

    // Players should never go below zero coins

    @Test
    fun `red cards cannot steal more than active player has`() {
        val players = listOf(
            player(1, 1),
            player(2, 0)
        )

        val cafe = card(
            cardType = CardType.CAFE,
            color = CardColor.RED,
            income = 5
        )

        whenever(cardDao.findByActivationNumber(3))
            .thenReturn(listOf(cafe))

        whenever(playerDao.getPlayers(1))
            .thenReturn(players)

        whenever(playerCardDao.findByPlayerId(1))
            .thenReturn(emptyList())

        whenever(playerCardDao.findByPlayerId(2))
            .thenReturn(listOf(playerCard(CardType.CAFE, 1)))

        service.processEarnings(1, 3, 1)

        verify(playerDao).updateCoins(1, 0)
        verify(playerDao).updateCoins(2, 1)
    }

    // Stadium-like effects should steal from everyone

    @Test
    fun `purple all players steals from each opponent`() {
        val players = listOf(
            player(1, 3),
            player(2, 3),
            player(3, 1)
        )

        val stadium = card(
            cardType = CardType.STADIUM,
            color = CardColor.PURPLE,
            income = 2,
            paymentSource = PaymentSource.ALL_PLAYERS
        )

        whenever(cardDao.findByActivationNumber(6))
            .thenReturn(listOf(stadium))

        whenever(playerDao.getPlayers(1))
            .thenReturn(players)

        whenever(playerCardDao.findByPlayerId(1))
            .thenReturn(listOf(playerCard(CardType.STADIUM, 1)))

        whenever(playerCardDao.findByPlayerId(2))
            .thenReturn(emptyList())

        whenever(playerCardDao.findByPlayerId(3))
            .thenReturn(emptyList())

        service.processEarnings(1, 6, 1)

        verify(playerDao).updateCoins(1, 6)
        verify(playerDao).updateCoins(2, 1)
        verify(playerDao).updateCoins(3, 0)
    }

    // After resolving effects the game should enter buy phase

    @Test
    fun `resolveEffects advances phase`() {
        val game = GameModel(
            id = 1, status = GameStatus.IN_PROGRESS, hostUserId = 1, lobbyCode = "TEST",
            maxPlayers = 4, currentTurnIndex = 0, turnPhase = TurnPhase.RESOLVE_EFFECTS,
            lastDiceRoll = 1, roundNumber = 1, hasPurchasedThisTurn = false, rerolledThisTurn = false
        )

        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenReturn(game)
        whenever(gameDao.tryTransitionPhase(1, TurnPhase.RESOLVE_EFFECTS, TurnPhase.BUY_OR_BUILD))
            .thenReturn(true)

        whenever(playerDao.getPlayers(1))
            .thenReturn(listOf(player(1, 3)))

        whenever(cardDao.findByActivationNumber(anyInt()))
            .thenReturn(emptyList())

        whenever(playerCardDao.findByPlayerId(anyInt()))
            .thenReturn(emptyList())

        service.resolveEffects(1)

        verify(gameDao).tryTransitionPhase(1, TurnPhase.RESOLVE_EFFECTS, TurnPhase.BUY_OR_BUILD)
    }

    // Invalid phases should fail immediately

    @Test
    fun `resolveEffects throws when phase is invalid`() {
        val game = GameModel(
            id = 1, status = GameStatus.IN_PROGRESS, hostUserId = 1, lobbyCode = "TEST",
            maxPlayers = 4, currentTurnIndex = 0, turnPhase = TurnPhase.ROLL_DICE,
            lastDiceRoll = null, roundNumber = 1, hasPurchasedThisTurn = false, rerolledThisTurn = false
        )

        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenReturn(game)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.resolveEffects(1)
        }
        assertEquals("DICE_ROLL_REQUIRED", ex.errorCode)
    }

    @Test
    fun `resolveEffects rejects duplicate resolution in buy phase`() {
        val game = GameModel(
            id = 1, status = GameStatus.IN_PROGRESS, hostUserId = 1, lobbyCode = "TEST",
            maxPlayers = 4, currentTurnIndex = 0, turnPhase = TurnPhase.BUY_OR_BUILD,
            lastDiceRoll = 4, roundNumber = 1, hasPurchasedThisTurn = false, rerolledThisTurn = false
        )
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(game)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.resolveEffects(1)
        }

        assertEquals("EFFECTS_ALREADY_RESOLVED", ex.errorCode)
        verify(gameDao, never()).tryTransitionPhase(1, TurnPhase.RESOLVE_EFFECTS, TurnPhase.BUY_OR_BUILD)
        verify(playerDao, never()).getPlayers(anyInt())
    }

    @Test
    fun `resolveEffects rejects duplicate resolution in end turn phase`() {
        val game = GameModel(
            id = 1, status = GameStatus.IN_PROGRESS, hostUserId = 1, lobbyCode = "TEST",
            maxPlayers = 4, currentTurnIndex = 0, turnPhase = TurnPhase.END_TURN,
            lastDiceRoll = 4, roundNumber = 1, hasPurchasedThisTurn = false, rerolledThisTurn = false
        )
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(game)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.resolveEffects(1)
        }

        assertEquals("EFFECTS_ALREADY_RESOLVED", ex.errorCode)
        verify(gameDao, never()).tryTransitionPhase(1, TurnPhase.RESOLVE_EFFECTS, TurnPhase.BUY_OR_BUILD)
        verify(playerDao, never()).getPlayers(anyInt())
    }

    @Test
    fun `resolveEffects requires stored dice roll in resolve phase`() {
        val game = GameModel(
            id = 1, status = GameStatus.IN_PROGRESS, hostUserId = 1, lobbyCode = "TEST",
            maxPlayers = 4, currentTurnIndex = 0, turnPhase = TurnPhase.RESOLVE_EFFECTS,
            lastDiceRoll = null, roundNumber = 1, hasPurchasedThisTurn = false, rerolledThisTurn = false
        )
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(game)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.resolveEffects(1)
        }

        assertEquals("DICE_ROLL_REQUIRED", ex.errorCode)
        verify(gameDao, never()).tryTransitionPhase(1, TurnPhase.RESOLVE_EFFECTS, TurnPhase.BUY_OR_BUILD)
        verify(playerDao, never()).getPlayers(anyInt())
    }

    @Test
    fun `resolveEffects rejects stale transition without applying earnings`() {
        val game = GameModel(
            id = 1, status = GameStatus.IN_PROGRESS, hostUserId = 1, lobbyCode = "TEST",
            maxPlayers = 4, currentTurnIndex = 0, turnPhase = TurnPhase.RESOLVE_EFFECTS,
            lastDiceRoll = 4, roundNumber = 1, hasPurchasedThisTurn = false, rerolledThisTurn = false
        )
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(game)
        whenever(gameDao.tryTransitionPhase(1, TurnPhase.RESOLVE_EFFECTS, TurnPhase.BUY_OR_BUILD))
            .thenReturn(false)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.resolveEffects(1)
        }

        assertEquals("EFFECTS_ALREADY_RESOLVED", ex.errorCode)
        verify(playerDao, never()).getPlayers(anyInt())
    }

    @Test
    fun `resolveEffects rejects missing active player after phase transition`() {
        val game = GameModel(
            id = 1, status = GameStatus.IN_PROGRESS, hostUserId = 1, lobbyCode = "TEST",
            maxPlayers = 4, currentTurnIndex = 1, turnPhase = TurnPhase.RESOLVE_EFFECTS,
            lastDiceRoll = 4, roundNumber = 1, hasPurchasedThisTurn = false, rerolledThisTurn = false
        )
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(game)
        whenever(gameDao.tryTransitionPhase(1, TurnPhase.RESOLVE_EFFECTS, TurnPhase.BUY_OR_BUILD))
            .thenReturn(true)
        whenever(playerDao.getPlayers(1)).thenReturn(listOf(player(1, 3)))

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.resolveEffects(1)
        }

        assertEquals("NO_ACTIVE_PLAYER", ex.errorCode)
        verify(cardDao, never()).findByActivationNumber(anyInt())
    }

    @Test
    fun `shopping mall increases red cup card coin theft for owner`() {
        val players = listOf(
            player(1, 5),
            player(2, 1)
        )

        // cafe is a CUP, RED, base income 2
        val cafe = card(
            cardType = CardType.CAFE,
            color = CardColor.RED,
            income = 2
        )

        // ensure the card is treated as CUP in the helper
        val RedCupWithCupType = cafe.copy(establishmentType = EstablishmentType.CUP)

        whenever(cardDao.findByActivationNumber(3)).thenReturn(listOf(RedCupWithCupType))
        whenever(playerDao.getPlayers(1)).thenReturn(players)
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(emptyList())
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(listOf(playerCard(CardType.CAFE, 1)))

        // P2 has built SHOPPING_MALL -> cafe income increases by +1 (2 -> 3)
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.SHOPPING_MALL))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.SHOPPING_MALL, isBuilt = true))

        service.processEarnings(1, 3, 1)

        // Transfer should be 3 from P1 to P2
        verify(playerDao).updateCoins(1, 2) // 5 - 3 = 2
        verify(playerDao).updateCoins(2, 4) // 1 + 3 = 4
    }

    @Test
    fun `shopping mall does not trigger bonus for red cup cards for active player`() {
        val players = listOf(
            player(1, 5),
            player(2, 1)
        )

        // cafe is a CUP, RED, base income 2
        val cafe = card(
            cardType = CardType.CAFE,
            color = CardColor.RED,
            income = 2
        )

        // ensure the card is treated as CUP in the helper
        val RedCupWithCupType = cafe.copy(establishmentType = EstablishmentType.CUP)

        whenever(cardDao.findByActivationNumber(3)).thenReturn(listOf(RedCupWithCupType))
        whenever(playerDao.getPlayers(1)).thenReturn(players)
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(listOf(playerCard(CardType.CAFE, 1)))
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(emptyList())

        // P2 has built SHOPPING_MALL -> cafe income increases by +1 (2 -> 3)
        whenever(playerLandmarkDao.findByPlayerIdAndType(1, LandmarkType.SHOPPING_MALL))
            .thenReturn(PlayerLandmarkModel(playerId = 1, landmarkType = LandmarkType.SHOPPING_MALL, isBuilt = true))

        service.processEarnings(1, 3, 1)

        // Transfer should be 0 from P2 to P1
        verify(playerDao, never()).updateCoins(eq(1), any())
        verify(playerDao, never()).updateCoins(eq(2), any()) 
    }


    @Test
    fun `shopping mall increases coin theft for multiple red cup cards for owner correctly`() {
        val players = listOf(
            player(1, 10),
            player(2, 1)
        )

        // cafe is a CUP, RED, base income 2
        val cafe = card(
            cardType = CardType.CAFE,
            color = CardColor.RED,
            income = 2
        )

        // ensure the card is treated as CUP in the helper
        val RedCupWithCupType = cafe.copy(establishmentType = EstablishmentType.CUP)

        whenever(cardDao.findByActivationNumber(3)).thenReturn(listOf(RedCupWithCupType))
        whenever(playerDao.getPlayers(1)).thenReturn(players)
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(emptyList())
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(listOf(playerCard(CardType.CAFE, 3)))

        // P2 has built SHOPPING_MALL -> cafe income increases by +1 (2 -> 3)
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.SHOPPING_MALL))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.SHOPPING_MALL, isBuilt = true))

        service.processEarnings(1, 3, 1)

        // Transfer should be (2+1)*3 = 9 from P1 to P2
        verify(playerDao).updateCoins(1, 1) // 10 - 9 = 1
        verify(playerDao).updateCoins(2, 10) // 1 + 9 = 10
    }

    @Test
    fun `shopping mall increases green bread income for active player`() {
        val players = listOf(
            player(1, 3),
            player(2, 3)
        )

        // create a green Bread card
        val greenBread = card(
            cardType = CardType.BAKERY,
            color = CardColor.GREEN,
            income = 1
        )

        // ensure the card is treated as Bread in the helper
        val greenBreadWithBreadType = greenBread.copy(establishmentType = EstablishmentType.BREAD)

        whenever(cardDao.findByActivationNumber(2)).thenReturn(listOf(greenBreadWithBreadType))
        whenever(playerDao.getPlayers(1)).thenReturn(players)
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(listOf(playerCard(CardType.BAKERY, 1)))
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(listOf(playerCard(CardType.BAKERY, 1)))

        // Active player (P1) has built SHOPPING_MALL
        whenever(playerLandmarkDao.findByPlayerIdAndType(1, LandmarkType.SHOPPING_MALL))
            .thenReturn(PlayerLandmarkModel(playerId = 1, landmarkType = LandmarkType.SHOPPING_MALL, isBuilt = true))

        service.processEarnings(1, 2, 1)

        // P1 should receive (1 + 1) = 2 from their green Bread; P2 unchanged
        verify(playerDao).updateCoins(1, 5) // 3 + 2 = 5
        verify(playerDao, never()).updateCoins(2, 5)
    }

    @Test
    fun `shopping mall increases income for multiple green bread cards correctly for active player`() {
        val players = listOf(
            player(1, 3),
            player(2, 3)
        )

        // create a green Bread card
        val greenBread = card(
            cardType = CardType.BAKERY,
            color = CardColor.GREEN,
            income = 1
        )

        // ensure the card is treated as Bread in the helper
        val greenBreadWithBreadType = greenBread.copy(establishmentType = EstablishmentType.BREAD)

        whenever(cardDao.findByActivationNumber(2)).thenReturn(listOf(greenBreadWithBreadType))
        whenever(playerDao.getPlayers(1)).thenReturn(players)
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(listOf(playerCard(CardType.BAKERY, 3)))
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(listOf(playerCard(CardType.BAKERY, 1)))

        // Active player (P1) has built SHOPPING_MALL
        whenever(playerLandmarkDao.findByPlayerIdAndType(1, LandmarkType.SHOPPING_MALL))
            .thenReturn(PlayerLandmarkModel(playerId = 1, landmarkType = LandmarkType.SHOPPING_MALL, isBuilt = true))

        service.processEarnings(1, 2, 1)

        // P1 should receive (1 + 1)*3 = 6 from their green Bread; P2 unchanged
        verify(playerDao).updateCoins(1, 9) // 3 + 6 = 9
    }

    @Test
    fun `shopping mall does not affect green bread income for non-active player`() {
        val players = listOf(
            player(1, 3),
            player(2, 3)
        )

        // create a green Bread card
        val greenBread = card(
            cardType = CardType.BAKERY,
            color = CardColor.GREEN,
            income = 1
        )

        // ensure the card is treated as Bread in the helper
        val greenBreadWithBreadType = greenBread.copy(establishmentType = EstablishmentType.BREAD)

        whenever(cardDao.findByActivationNumber(2)).thenReturn(listOf(greenBreadWithBreadType))
        whenever(playerDao.getPlayers(1)).thenReturn(players)
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(listOf(playerCard(CardType.BAKERY, 1)))
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(listOf(playerCard(CardType.BAKERY, 1)))

        // Active player (P1) has built SHOPPING_MALL
        whenever(playerLandmarkDao.findByPlayerIdAndType(1, LandmarkType.SHOPPING_MALL))
            .thenReturn(PlayerLandmarkModel(playerId = 1, landmarkType = LandmarkType.SHOPPING_MALL, isBuilt = true))

        service.processEarnings(1, 2, 2)

        // P1 should not receive bonus from their green Bread; P2 receives +1
        verify(playerDao, never()).updateCoins(1, 5) // 3 + 2 = 5
        verify(playerDao).updateCoins(2, 4) //3 + 1
    }

    @Test
    fun `initialized but not built shopping mall does not give bonuses to green cards`() {
        val players = listOf(
            player(1, 3),
            player(2, 3)
        )

        // create a green Bread card
        val greenBread = card(
            cardType = CardType.BAKERY,
            color = CardColor.GREEN,
            income = 1
        )

        // ensure the card is treated as Bread in the helper
        val greenBreadWithBreadType = greenBread.copy(establishmentType = EstablishmentType.BREAD)

        whenever(cardDao.findByActivationNumber(2)).thenReturn(listOf(greenBreadWithBreadType))
        whenever(playerDao.getPlayers(1)).thenReturn(players)
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(listOf(playerCard(CardType.BAKERY, 1)))
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(listOf(playerCard(CardType.BAKERY, 1)))

        // Active player (P1) has built SHOPPING_MALL
        whenever(playerLandmarkDao.findByPlayerIdAndType(1, LandmarkType.SHOPPING_MALL))
            .thenReturn(PlayerLandmarkModel(playerId = 1, landmarkType = LandmarkType.SHOPPING_MALL, isBuilt = false))

        service.processEarnings(1, 2, 1)

        // P1 should receive 1 from their green Bread (no bonus); P2 unchanged
        verify(playerDao).updateCoins(1, 4) // 3 + 1 = 4
        verify(playerDao, never()).updateCoins(2, 4)
    }

    @Test
    fun `initialized but not built shopping mall does not give bonuses to red cards`() {
        val players = listOf(
            player(1, 5),
            player(2, 3)
        )

        // cafe is a CUP, RED, base income 2
        val cafe = card(
            cardType = CardType.CAFE,
            color = CardColor.RED,
            income = 2
        )

        // ensure the card is treated as Bread in the helper
        val redCafeWithCafeType = cafe.copy(establishmentType = EstablishmentType.CUP)

        whenever(cardDao.findByActivationNumber(2)).thenReturn(listOf(redCafeWithCafeType))
        whenever(playerDao.getPlayers(1)).thenReturn(players)
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(listOf(playerCard(CardType.BAKERY, 1)))
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(listOf(playerCard(CardType.CAFE, 1)))

        // Active player (P2) has built SHOPPING_MALL
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.SHOPPING_MALL))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.SHOPPING_MALL, isBuilt = false))

        service.processEarnings(1, 2, 1)

        // P2 should receive 2 from their red CUP (no bonus); P1 loses 2
        verify(playerDao).updateCoins(1, 3) // 5 - 2 = 3
        verify(playerDao).updateCoins(2, 5) // 3 + 2 = 5
    }

    private fun player(id: Int, coins: Int): PlayerModel =
        PlayerModel(id = id, gameId = 1, userId = id, turnOrder = 0, coins = coins, lastSeenAt = null)

    private fun playerCard(cardType: CardType, quantity: Int): PlayerCardModel =
        PlayerCardModel(playerId = 1, cardType = cardType, quantity = quantity)

    private fun card(
        cardType: CardType,
        color: CardColor,
        income: Int,
        paymentSource: PaymentSource = PaymentSource.BANK
    ): CardModel =
        CardModel(
            id = 0, cardType = cardType, cost = 0, income = income,
            color = color, establishmentType = EstablishmentType.WHEAT,
            paymentSource = paymentSource
        )
}
