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

    @Test
    fun `purple none payment source does not award coins`() {
        val players = listOf(
            player(1, 3),
            player(2, 3)
        )

        val businessCenter = card(
            cardType = CardType.BUSINESS_CENTER,
            color = CardColor.PURPLE,
            income = 0,
            paymentSource = PaymentSource.NONE
        )

        whenever(cardDao.findByActivationNumber(6)).thenReturn(listOf(businessCenter))
        whenever(playerDao.getPlayers(1)).thenReturn(players)
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(listOf(playerCard(CardType.BUSINESS_CENTER, 1)))
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(emptyList())

        val deltas = service.processEarnings(1, 6, 1)

        assertEquals(emptyMap<Int, Int>(), deltas)
        verify(playerDao, never()).updateCoins(anyInt(), anyInt())
    }

    @Test
    fun `purple chosen player payment source still credits bank pending tv station steal`() {
        // TV Station (CHOSEN_PLAYER) is intentionally left on the pre-existing
        // bank-credit path here; the proper steal is handled by #433. Business
        // Center must not regress this card, so it keeps awarding the active
        // player from the bank.
        val players = listOf(
            player(1, 3),
            player(2, 3)
        )

        val tvStation = card(
            cardType = CardType.TV_STATION,
            color = CardColor.PURPLE,
            income = 5,
            paymentSource = PaymentSource.CHOSEN_PLAYER
        )

        whenever(cardDao.findByActivationNumber(6)).thenReturn(listOf(tvStation))
        whenever(playerDao.getPlayers(1)).thenReturn(players)
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(listOf(playerCard(CardType.TV_STATION, 1)))
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(emptyList())

        val deltas = service.processEarnings(1, 6, 1)

        assertEquals(mapOf(1 to 5), deltas)
        verify(playerDao).updateCoins(1, 8)
        verify(playerDao, never()).updateCoins(eq(2), anyInt())
    }

    @Test
    fun `business center swaps one non-major card with target player`() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(businessCenterGame())
        whenever(playerDao.getPlayers(1)).thenReturn(listOf(player(1, 3), player(2, 3)))
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(
            listOf(
                playerCard(CardType.BUSINESS_CENTER, 1),
                playerCard(CardType.BAKERY, 1),
            )
        )
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(listOf(playerCard(CardType.RANCH, 2)))
        whenever(cardDao.findByCardType(CardType.BAKERY)).thenReturn(
            card(CardType.BAKERY, CardColor.GREEN, 1).copy(establishmentType = EstablishmentType.BREAD)
        )
        whenever(cardDao.findByCardType(CardType.RANCH)).thenReturn(
            card(CardType.RANCH, CardColor.BLUE, 1).copy(establishmentType = EstablishmentType.COW)
        )
        whenever(gameDao.tryMarkBusinessCenterUsedThisTurn(1)).thenReturn(true)

        service.swapBusinessCenterCard(1, 1, 2, CardType.BAKERY, CardType.RANCH)

        verify(gameDao).tryMarkBusinessCenterUsedThisTurn(1)
        verify(playerCardDao).upsert(1, CardType.BAKERY, 0)
        verify(playerCardDao).upsert(2, CardType.RANCH, 1)
        verify(playerCardDao).upsert(1, CardType.RANCH, 1)
        verify(playerCardDao).upsert(2, CardType.BAKERY, 1)
    }

    @Test
    fun `business center rejects major establishment swaps`() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(businessCenterGame())
        whenever(playerDao.getPlayers(1)).thenReturn(listOf(player(1, 3), player(2, 3)))
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(
            listOf(
                playerCard(CardType.BUSINESS_CENTER, 1),
                playerCard(CardType.BAKERY, 1),
            )
        )
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(listOf(playerCard(CardType.STADIUM, 1)))
        whenever(cardDao.findByCardType(CardType.BAKERY)).thenReturn(
            card(CardType.BAKERY, CardColor.GREEN, 1).copy(establishmentType = EstablishmentType.BREAD)
        )
        whenever(cardDao.findByCardType(CardType.STADIUM)).thenReturn(
            card(CardType.STADIUM, CardColor.PURPLE, 2).copy(establishmentType = EstablishmentType.MAJOR)
        )

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.swapBusinessCenterCard(1, 1, 2, CardType.BAKERY, CardType.STADIUM)
        }

        assertEquals("INVALID_BUSINESS_CENTER_CARD", ex.errorCode)
        verify(playerCardDao, never()).upsert(anyInt(), any(), anyInt())
    }

    @Test
    fun `business center rejects already used turn flag`() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(
            businessCenterGame(businessCenterUsedThisTurn = true)
        )

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.swapBusinessCenterCard(1, 1, 2, CardType.BAKERY, CardType.RANCH)
        }

        assertEquals("BUSINESS_CENTER_ALREADY_USED", ex.errorCode)
        verify(playerDao, never()).getPlayers(anyInt())
        verify(gameDao, never()).tryMarkBusinessCenterUsedThisTurn(anyInt())
    }

    @Test
    fun `business center rejects swap before it is active`() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(
            businessCenterGame(turnPhase = TurnPhase.RESOLVE_EFFECTS, lastDiceRoll = 6)
        )

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.swapBusinessCenterCard(1, 1, 2, CardType.BAKERY, CardType.RANCH)
        }

        assertEquals("BUSINESS_CENTER_NOT_ACTIVE", ex.errorCode)
        verify(playerDao, never()).getPlayers(anyInt())
    }

    @Test
    fun `business center rejects swap when roll was not six`() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(businessCenterGame(lastDiceRoll = 5))

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.swapBusinessCenterCard(1, 1, 2, CardType.BAKERY, CardType.RANCH)
        }

        assertEquals("BUSINESS_CENTER_NOT_ACTIVE", ex.errorCode)
        verify(playerDao, never()).getPlayers(anyInt())
    }

    @Test
    fun `business center rejects missing active player`() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(businessCenterGame(currentTurnIndex = 2))
        whenever(playerDao.getPlayers(1)).thenReturn(listOf(player(1, 3), player(2, 3)))

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.swapBusinessCenterCard(1, 1, 2, CardType.BAKERY, CardType.RANCH)
        }

        assertEquals("NO_ACTIVE_PLAYER", ex.errorCode)
        verify(playerCardDao, never()).findByPlayerId(anyInt())
    }

    @Test
    fun `business center rejects non active player caller`() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(businessCenterGame())
        whenever(playerDao.getPlayers(1)).thenReturn(listOf(player(1, 3), player(2, 3)))

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.swapBusinessCenterCard(1, 2, 1, CardType.BAKERY, CardType.RANCH)
        }

        assertEquals("NOT_YOUR_TURN", ex.errorCode)
        verify(playerCardDao, never()).findByPlayerId(anyInt())
    }

    @Test
    fun `business center rejects target outside game`() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(businessCenterGame())
        whenever(playerDao.getPlayers(1)).thenReturn(listOf(player(1, 3), player(2, 3)))

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.swapBusinessCenterCard(1, 1, 99, CardType.BAKERY, CardType.RANCH)
        }

        assertEquals("INVALID_SWAP_TARGET", ex.errorCode)
        verify(playerCardDao, never()).findByPlayerId(anyInt())
    }

    @Test
    fun `business center rejects active player as target`() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(businessCenterGame())
        whenever(playerDao.getPlayers(1)).thenReturn(listOf(player(1, 3), player(2, 3)))

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.swapBusinessCenterCard(1, 1, 1, CardType.BAKERY, CardType.RANCH)
        }

        assertEquals("INVALID_SWAP_TARGET", ex.errorCode)
        verify(playerCardDao, never()).findByPlayerId(anyInt())
    }

    @Test
    fun `business center rejects same card type exchange`() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(businessCenterGame())
        whenever(playerDao.getPlayers(1)).thenReturn(listOf(player(1, 3), player(2, 3)))

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.swapBusinessCenterCard(1, 1, 2, CardType.BAKERY, CardType.BAKERY)
        }

        assertEquals("INVALID_BUSINESS_CENTER_CARD", ex.errorCode)
        verify(playerCardDao, never()).findByPlayerId(anyInt())
    }

    @Test
    fun `business center rejects player without business center`() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(businessCenterGame())
        whenever(playerDao.getPlayers(1)).thenReturn(listOf(player(1, 3), player(2, 3)))
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(listOf(playerCard(CardType.BAKERY, 1)))
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(listOf(playerCard(CardType.RANCH, 1)))

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.swapBusinessCenterCard(1, 1, 2, CardType.BAKERY, CardType.RANCH)
        }

        assertEquals("BUSINESS_CENTER_NOT_OWNED", ex.errorCode)
        verify(cardDao, never()).findByCardType(any())
        verify(playerCardDao, never()).upsert(anyInt(), any(), anyInt())
    }

    @Test
    fun `business center rejects unknown offered card definition`() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(businessCenterGame())
        whenever(playerDao.getPlayers(1)).thenReturn(listOf(player(1, 3), player(2, 3)))
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(
            listOf(playerCard(CardType.BUSINESS_CENTER, 1), playerCard(CardType.BAKERY, 1))
        )
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(listOf(playerCard(CardType.RANCH, 1)))
        whenever(cardDao.findByCardType(CardType.BAKERY)).thenReturn(null)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.swapBusinessCenterCard(1, 1, 2, CardType.BAKERY, CardType.RANCH)
        }

        assertEquals("CARD_NOT_FOUND", ex.errorCode)
        verify(playerCardDao, never()).upsert(anyInt(), any(), anyInt())
    }

    @Test
    fun `business center rejects unknown requested card definition`() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(businessCenterGame())
        whenever(playerDao.getPlayers(1)).thenReturn(listOf(player(1, 3), player(2, 3)))
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(
            listOf(playerCard(CardType.BUSINESS_CENTER, 1), playerCard(CardType.BAKERY, 1))
        )
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(listOf(playerCard(CardType.RANCH, 1)))
        whenever(cardDao.findByCardType(CardType.BAKERY)).thenReturn(
            card(CardType.BAKERY, CardColor.GREEN, 1).copy(establishmentType = EstablishmentType.BREAD)
        )
        whenever(cardDao.findByCardType(CardType.RANCH)).thenReturn(null)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.swapBusinessCenterCard(1, 1, 2, CardType.BAKERY, CardType.RANCH)
        }

        assertEquals("CARD_NOT_FOUND", ex.errorCode)
        verify(playerCardDao, never()).upsert(anyInt(), any(), anyInt())
    }

    @Test
    fun `business center rejects cards not owned by both players`() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(businessCenterGame())
        whenever(playerDao.getPlayers(1)).thenReturn(listOf(player(1, 3), player(2, 3)))
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(listOf(playerCard(CardType.BUSINESS_CENTER, 1)))
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(listOf(playerCard(CardType.RANCH, 1)))
        whenever(cardDao.findByCardType(CardType.BAKERY)).thenReturn(
            card(CardType.BAKERY, CardColor.GREEN, 1).copy(establishmentType = EstablishmentType.BREAD)
        )
        whenever(cardDao.findByCardType(CardType.RANCH)).thenReturn(
            card(CardType.RANCH, CardColor.BLUE, 1).copy(establishmentType = EstablishmentType.COW)
        )

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.swapBusinessCenterCard(1, 1, 2, CardType.BAKERY, CardType.RANCH)
        }

        assertEquals("CARD_NOT_OWNED", ex.errorCode)
        verify(playerCardDao, never()).upsert(anyInt(), any(), anyInt())
        verify(gameDao, never()).tryMarkBusinessCenterUsedThisTurn(anyInt())
    }

    @Test
    fun `business center rejects second swap when mark fails`() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(businessCenterGame())
        whenever(playerDao.getPlayers(1)).thenReturn(listOf(player(1, 3), player(2, 3)))
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(
            listOf(
                playerCard(CardType.BUSINESS_CENTER, 1),
                playerCard(CardType.BAKERY, 1),
            )
        )
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(listOf(playerCard(CardType.RANCH, 1)))
        whenever(cardDao.findByCardType(CardType.BAKERY)).thenReturn(
            card(CardType.BAKERY, CardColor.GREEN, 1).copy(establishmentType = EstablishmentType.BREAD)
        )
        whenever(cardDao.findByCardType(CardType.RANCH)).thenReturn(
            card(CardType.RANCH, CardColor.BLUE, 1).copy(establishmentType = EstablishmentType.COW)
        )
        whenever(gameDao.tryMarkBusinessCenterUsedThisTurn(1)).thenReturn(false)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            service.swapBusinessCenterCard(1, 1, 2, CardType.BAKERY, CardType.RANCH)
        }

        assertEquals("BUSINESS_CENTER_ALREADY_USED", ex.errorCode)
        verify(playerCardDao, never()).upsert(anyInt(), any(), anyInt())
    }

    @Test
    fun `business center increments existing swapped card quantities`() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(businessCenterGame())
        whenever(playerDao.getPlayers(1)).thenReturn(listOf(player(1, 3), player(2, 3)))
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(
            listOf(
                playerCard(CardType.BUSINESS_CENTER, 1),
                playerCard(CardType.BAKERY, 2),
                playerCard(CardType.RANCH, 3),
            )
        )
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(
            listOf(
                playerCard(CardType.RANCH, 4),
                playerCard(CardType.BAKERY, 5),
            )
        )
        whenever(cardDao.findByCardType(CardType.BAKERY)).thenReturn(
            card(CardType.BAKERY, CardColor.GREEN, 1).copy(establishmentType = EstablishmentType.BREAD)
        )
        whenever(cardDao.findByCardType(CardType.RANCH)).thenReturn(
            card(CardType.RANCH, CardColor.BLUE, 1).copy(establishmentType = EstablishmentType.COW)
        )
        whenever(gameDao.tryMarkBusinessCenterUsedThisTurn(1)).thenReturn(true)

        service.swapBusinessCenterCard(1, 1, 2, CardType.BAKERY, CardType.RANCH)

        verify(playerCardDao).upsert(1, CardType.BAKERY, 1)
        verify(playerCardDao).upsert(2, CardType.RANCH, 3)
        verify(playerCardDao).upsert(1, CardType.RANCH, 4)
        verify(playerCardDao).upsert(2, CardType.BAKERY, 6)
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

    // Factory / market green cards multiply income by owned establishments (issue #432)

    @Test
    fun `cheese factory pays three coins per owned cow card`() {
        val players = listOf(player(1, 0))

        val cheeseFactory = card(
            cardType = CardType.CHEESE_FACTORY,
            color = CardColor.GREEN,
            income = 3
        ).copy(establishmentType = EstablishmentType.FACTORY)

        whenever(cardDao.findByActivationNumber(7)).thenReturn(listOf(cheeseFactory))
        whenever(cardDao.findAll()).thenReturn(seededCards)
        whenever(playerDao.getPlayers(1)).thenReturn(players)
        // 1 Cheese Factory + 2 Ranch (COW): income = 3 * 2 cows = 6.
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(
            listOf(
                playerCard(CardType.CHEESE_FACTORY, 1),
                playerCard(CardType.RANCH, 2),
            )
        )

        service.processEarnings(1, 7, 1)

        verify(playerDao).updateCoins(1, 6)
    }

    @Test
    fun `cheese factory multiplies by both quantity and cow count`() {
        val players = listOf(player(1, 0))

        val cheeseFactory = card(
            cardType = CardType.CHEESE_FACTORY,
            color = CardColor.GREEN,
            income = 3
        ).copy(establishmentType = EstablishmentType.FACTORY)

        whenever(cardDao.findByActivationNumber(7)).thenReturn(listOf(cheeseFactory))
        whenever(cardDao.findAll()).thenReturn(seededCards)
        whenever(playerDao.getPlayers(1)).thenReturn(players)
        // 2 Cheese Factories + 3 Ranch (COW): income = 2 * 3 * 3 = 18.
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(
            listOf(
                playerCard(CardType.CHEESE_FACTORY, 2),
                playerCard(CardType.RANCH, 3),
            )
        )

        service.processEarnings(1, 7, 1)

        verify(playerDao).updateCoins(1, 18)
    }

    @Test
    fun `cheese factory pays nothing without any cow cards`() {
        val players = listOf(player(1, 0))

        val cheeseFactory = card(
            cardType = CardType.CHEESE_FACTORY,
            color = CardColor.GREEN,
            income = 3
        ).copy(establishmentType = EstablishmentType.FACTORY)

        whenever(cardDao.findByActivationNumber(7)).thenReturn(listOf(cheeseFactory))
        whenever(cardDao.findAll()).thenReturn(seededCards)
        whenever(playerDao.getPlayers(1)).thenReturn(players)
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(listOf(playerCard(CardType.CHEESE_FACTORY, 1)))

        val deltas = service.processEarnings(1, 7, 1)

        assertEquals(emptyMap<Int, Int>(), deltas)
        verify(playerDao, never()).updateCoins(anyInt(), anyInt())
    }

    @Test
    fun `furniture factory pays three coins per owned gear card`() {
        val players = listOf(player(1, 0))

        val furnitureFactory = card(
            cardType = CardType.FURNITURE_FACTORY,
            color = CardColor.GREEN,
            income = 3
        ).copy(establishmentType = EstablishmentType.FACTORY)

        whenever(cardDao.findByActivationNumber(8)).thenReturn(listOf(furnitureFactory))
        whenever(cardDao.findAll()).thenReturn(seededCards)
        whenever(playerDao.getPlayers(1)).thenReturn(players)
        // 1 Furniture Factory + 1 Forest + 1 Mine (both GEAR): income = 3 * 2 gears = 6.
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(
            listOf(
                playerCard(CardType.FURNITURE_FACTORY, 1),
                playerCard(CardType.FOREST, 1),
                playerCard(CardType.MINE, 1),
            )
        )

        service.processEarnings(1, 8, 1)

        verify(playerDao).updateCoins(1, 6)
    }

    @Test
    fun `fruit and vegetable market pays two coins per owned wheat card`() {
        val players = listOf(player(1, 0))

        val market = card(
            cardType = CardType.FRUIT_AND_VEGETABLE_MARKET,
            color = CardColor.GREEN,
            income = 2
        ).copy(establishmentType = EstablishmentType.FRUIT)

        whenever(cardDao.findByActivationNumber(11)).thenReturn(listOf(market))
        whenever(cardDao.findAll()).thenReturn(seededCards)
        whenever(playerDao.getPlayers(1)).thenReturn(players)
        // 1 Market + 1 Wheat Field + 1 Apple Orchard (both WHEAT): income = 2 * 2 wheat = 4.
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(
            listOf(
                playerCard(CardType.FRUIT_AND_VEGETABLE_MARKET, 1),
                playerCard(CardType.WHEAT_FIELD, 1),
                playerCard(CardType.APPLE_ORCHARD, 1),
            )
        )

        service.processEarnings(1, 11, 1)

        verify(playerDao).updateCoins(1, 4)
    }

    // Card definitions covering every establishment symbol used by the multiplier tests.
    private val seededCards: List<CardModel> = listOf(
        card(CardType.WHEAT_FIELD, CardColor.BLUE, 1).copy(establishmentType = EstablishmentType.WHEAT),
        card(CardType.RANCH, CardColor.BLUE, 1).copy(establishmentType = EstablishmentType.COW),
        card(CardType.FOREST, CardColor.BLUE, 1).copy(establishmentType = EstablishmentType.GEAR),
        card(CardType.MINE, CardColor.BLUE, 5).copy(establishmentType = EstablishmentType.GEAR),
        card(CardType.APPLE_ORCHARD, CardColor.BLUE, 3).copy(establishmentType = EstablishmentType.WHEAT),
        card(CardType.CHEESE_FACTORY, CardColor.GREEN, 3).copy(establishmentType = EstablishmentType.FACTORY),
        card(CardType.FURNITURE_FACTORY, CardColor.GREEN, 3).copy(establishmentType = EstablishmentType.FACTORY),
        card(CardType.FRUIT_AND_VEGETABLE_MARKET, CardColor.GREEN, 2).copy(establishmentType = EstablishmentType.FRUIT),
    )

    private fun player(id: Int, coins: Int): PlayerModel =
        PlayerModel(id = id, gameId = 1, userId = id, turnOrder = 0, coins = coins, lastSeenAt = null)

    private fun playerCard(cardType: CardType, quantity: Int): PlayerCardModel =
        PlayerCardModel(playerId = 1, cardType = cardType, quantity = quantity)

    private fun businessCenterGame(
        currentTurnIndex: Int = 0,
        turnPhase: TurnPhase = TurnPhase.BUY_OR_BUILD,
        lastDiceRoll: Int? = 6,
        businessCenterUsedThisTurn: Boolean = false,
    ): GameModel =
        GameModel(
            id = 1,
            status = GameStatus.IN_PROGRESS,
            hostUserId = 1,
            lobbyCode = "TEST",
            maxPlayers = 4,
            currentTurnIndex = currentTurnIndex,
            turnPhase = turnPhase,
            lastDiceRoll = lastDiceRoll,
            roundNumber = 1,
            hasPurchasedThisTurn = false,
            businessCenterUsedThisTurn = businessCenterUsedThisTurn,
            rerolledThisTurn = false,
        )

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
