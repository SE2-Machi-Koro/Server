package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.Cards
import org.machikoro.server.database.GameMarketplace
import org.machikoro.server.database.Games
import org.machikoro.server.database.Landmarks
import org.machikoro.server.database.PlayerCards
import org.machikoro.server.database.PlayerLandmarks
import org.machikoro.server.database.Players
import org.machikoro.server.database.Users
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.dto.PurchaseType
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.service.interfaces.EarningsService
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

class PurchaseServiceIntegrationTest : AbstractDBSetup() {

    @Autowired
    private lateinit var purchaseService: PurchaseService

    @Autowired
    private lateinit var earningsService: EarningsService

    @Autowired
    private lateinit var gameDao: GameDao

    @Autowired
    private lateinit var playerDao: PlayerDao

    @Autowired
    private lateinit var playerCardDao: PlayerCardDao

    @Autowired
    private lateinit var playerLandmarkDao: PlayerLandmarkDao

    @Autowired
    private lateinit var gameMarketplaceDao: GameMarketplaceDao

    private var gameId: Int = 0
    private var activePlayerId: Int = 0
    private var inactivePlayerId: Int = 0

    @BeforeEach
    fun setup() {
        transaction {
            PlayerCards.deleteAll()
            PlayerLandmarks.deleteAll()
            Players.deleteAll()
            GameMarketplace.deleteAll()
            Games.deleteAll()
            Users.deleteAll()
            Cards.deleteAll()
            Landmarks.deleteAll()
        }

        transaction {
            Cards.insert {
                it[cardType] = CardType.BAKERY
                it[name] = "Bakery"
                it[cost] = 1
                it[diceMin] = 2
                it[diceMax] = 3
                it[income] = 1
            }

            Cards.insert {
                it[cardType] = CardType.STADIUM
                it[name] = "Stadium"
                it[cost] = 6
                it[diceMin] = 6
                it[diceMax] = 6
                it[income] = 2
            }

            Landmarks.insert {
                it[landmarkType] = LandmarkType.TRAIN_STATION
                it[name] = "Train Station"
                it[cost] = 4
            }

            val user1Id = (Users.insert {
                it[username] = "buyer1"
            } get Users.id).value

            val user2Id = (Users.insert {
                it[username] = "buyer2"
            } get Users.id).value

            gameId = (Games.insert {
                it[status] = GameStatus.IN_PROGRESS
                it[hostUserId] = user1Id
                it[currentTurnIndex] = 0
                it[lobbyCode] = (1000000..9999999).random().toString()
                it[maxPlayers] = 4
                it[turnPhase] = TurnPhase.BUY_OR_BUILD
                it[lastDiceRoll] = 2
                it[roundNumber] = 1
                it[hasPurchasedThisTurn] = false
            } get Games.id).value

            activePlayerId = playerDao.addPlayer(gameId, user1Id).id
            inactivePlayerId = playerDao.addPlayer(gameId, user2Id).id

            playerDao.updateCoins(activePlayerId, 6)
            gameMarketplaceDao.initForGame(gameId)
            playerLandmarkDao.initForPlayer(activePlayerId)
            playerLandmarkDao.initForPlayer(inactivePlayerId)
        }
    }

    @Test
    fun `establishment purchase updates coins ownership supply and purchase flag`() {
        purchaseService.purchase(
            gameId,
            PurchaseType.ESTABLISHMENT,
            CardType.BAKERY,
            null
        )

        val player = playerDao.findById(activePlayerId)!!
        val ownedCard = playerCardDao.findByPlayerId(activePlayerId).single()
        val supply = gameMarketplaceDao.findByGameIdAndType(gameId, CardType.BAKERY)!!
        val game = gameDao.findById(gameId)!!

        assertEquals(5, player.coins)
        assertEquals(CardType.BAKERY, ownedCard.cardType)
        assertEquals(1, ownedCard.quantity)
        assertEquals(5, supply.quantityAvailable)
        assertTrue(game.hasPurchasedThisTurn)
        assertEquals(TurnPhase.BUY_OR_BUILD, game.turnPhase)
    }

    @Test
    fun `landmark purchase updates coins built state and purchase flag`() {
        purchaseService.purchase(
            gameId,
            PurchaseType.LANDMARK,
            null,
            LandmarkType.TRAIN_STATION
        )

        val player = playerDao.findById(activePlayerId)!!
        val landmark = playerLandmarkDao
            .findByPlayerIdAndType(activePlayerId, LandmarkType.TRAIN_STATION)!!
        val game = gameDao.findById(gameId)!!

        assertEquals(2, player.coins)
        assertTrue(landmark.isBuilt)
        assertTrue(game.hasPurchasedThisTurn)
        assertEquals(TurnPhase.BUY_OR_BUILD, game.turnPhase)
    }

    @Test
    fun `advanceTurn resets purchase flag for next turn`() {
        purchaseService.purchase(
            gameId,
            PurchaseType.ESTABLISHMENT,
            CardType.BAKERY,
            null
        )

        gameDao.advanceTurn(
            gameId,
            nextTurnIndex = 1,
            roundNumber = 1
        )

        assertFalse(gameDao.findById(gameId)!!.hasPurchasedThisTurn)
    }

    @Test
    fun `second purchase in same turn is rejected without mutating state`() {
        purchaseService.purchase(
            gameId,
            PurchaseType.ESTABLISHMENT,
            CardType.BAKERY,
            null
        )

        val before = snapshot()

        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(
                gameId,
                PurchaseType.LANDMARK,
                null,
                LandmarkType.TRAIN_STATION
            )
        }

        assertEquals("PURCHASE_ALREADY_MADE", ex.errorCode)
        assertEquals(before, snapshot())
    }

    @Test
    fun `purchase outside buy or build phase does not mutate state`() {
        gameDao.updateTurnPhase(gameId, TurnPhase.RESOLVE_EFFECTS)
        val before = snapshot()

        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(
                gameId,
                PurchaseType.ESTABLISHMENT,
                CardType.BAKERY,
                null
            )
        }

        assertEquals("INVALID_TURN_PHASE", ex.errorCode)
        assertEquals(before, snapshot())
    }

    @Test
    fun `establishment purchase with insufficient coins does not mutate state`() {
        playerDao.updateCoins(activePlayerId, 0)
        val before = snapshot()

        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(
                gameId,
                PurchaseType.ESTABLISHMENT,
                CardType.BAKERY,
                null
            )
        }

        assertEquals("INSUFFICIENT_COINS", ex.errorCode)
        assertEquals(before, snapshot())
    }

    @Test
    fun `landmark purchase with insufficient coins does not mutate state`() {
        playerDao.updateCoins(activePlayerId, 3)
        val before = snapshot()

        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(
                gameId,
                PurchaseType.LANDMARK,
                null,
                LandmarkType.TRAIN_STATION
            )
        }

        assertEquals("INSUFFICIENT_COINS", ex.errorCode)
        assertEquals(before, snapshot())
    }

    @Test
    fun `unavailable establishment supply does not mutate state`() {
        gameMarketplaceDao.updateQuantity(gameId, CardType.BAKERY, 0)
        val before = snapshot()

        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(
                gameId,
                PurchaseType.ESTABLISHMENT,
                CardType.BAKERY,
                null
            )
        }

        assertEquals("CARD_UNAVAILABLE", ex.errorCode)
        assertEquals(before, snapshot())
    }

    @Test
    fun `already built landmark does not mutate state`() {
        playerLandmarkDao.markBuilt(activePlayerId, LandmarkType.TRAIN_STATION)
        val before = snapshot()

        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(
                gameId,
                PurchaseType.LANDMARK,
                null,
                LandmarkType.TRAIN_STATION
            )
        }

        assertEquals("LANDMARK_ALREADY_BUILT", ex.errorCode)
        assertEquals(before, snapshot())
    }

    @Test
    fun `malformed establishment purchase requests do not mutate state`() {
        val before = snapshot()

        val bothFields = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(
                gameId,
                PurchaseType.ESTABLISHMENT,
                CardType.BAKERY,
                LandmarkType.TRAIN_STATION
            )
        }

        val neitherField = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(
                gameId,
                PurchaseType.ESTABLISHMENT,
                null,
                null
            )
        }

        assertEquals("INVALID_PURCHASE_REQUEST", bothFields.errorCode)
        assertEquals("INVALID_PURCHASE_REQUEST", neitherField.errorCode)
        assertEquals(before, snapshot())
    }

    @Test
    fun `malformed landmark purchase requests do not mutate state`() {
        val before = snapshot()

        val bothFields = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(
                gameId,
                PurchaseType.LANDMARK,
                CardType.BAKERY,
                LandmarkType.TRAIN_STATION
            )
        }

        val neitherField = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(
                gameId,
                PurchaseType.LANDMARK,
                null,
                null
            )
        }

        assertEquals("INVALID_PURCHASE_REQUEST", bothFields.errorCode)
        assertEquals("INVALID_PURCHASE_REQUEST", neitherField.errorCode)
        assertEquals(before, snapshot())
    }

    @Test
    fun `purchased establishment contributes to earnings in a later turn`() {
        purchaseService.purchase(
            gameId,
            PurchaseType.ESTABLISHMENT,
            CardType.BAKERY,
            null
        )

        gameDao.advanceTurn(gameId, nextTurnIndex = 1, roundNumber = 1)
        gameDao.advanceTurn(gameId, nextTurnIndex = 0, roundNumber = 2)
        gameDao.updateAfterRoll(gameId, diceRoll = 2, phase = TurnPhase.RESOLVE_EFFECTS)

        earningsService.resolveEffects(gameId)

        assertEquals(6, playerDao.findById(activePlayerId)!!.coins)
    }

    private fun snapshot() = PurchaseStateSnapshot(
        activeCoins = playerDao.findById(activePlayerId)!!.coins,
        bakeryQuantity = playerCardDao.findByPlayerId(activePlayerId)
            .firstOrNull { it.cardType == CardType.BAKERY }
            ?.quantity
            ?: 0,
        bakerySupply = gameMarketplaceDao.findByGameIdAndType(gameId, CardType.BAKERY)
            ?.quantityAvailable
            ?: 0,
        trainStationBuilt = playerLandmarkDao.findByPlayerIdAndType(activePlayerId, LandmarkType.TRAIN_STATION)
            ?.isBuilt
            ?: false,
        hasPurchasedThisTurn = gameDao.findById(gameId)!!.hasPurchasedThisTurn,
    )

    private data class PurchaseStateSnapshot(
        val activeCoins: Int,
        val bakeryQuantity: Int,
        val bakerySupply: Int,
        val trainStationBuilt: Boolean,
        val hasPurchasedThisTurn: Boolean,
    )
}
