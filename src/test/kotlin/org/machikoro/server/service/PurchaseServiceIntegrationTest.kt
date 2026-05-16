package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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
import org.machikoro.server.database.CardActivationNumbers
import org.machikoro.server.database.Cards
import org.machikoro.server.database.TestDataSeeder
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.test.Test

class PurchaseServiceIntegrationTest : AbstractDBSetup() {

    @Autowired private lateinit var purchaseService: PurchaseService
    @Autowired private lateinit var earningsService: EarningsService
    @Autowired private lateinit var gameDao: GameDao
    @Autowired private lateinit var playerDao: PlayerDao
    @Autowired private lateinit var playerCardDao: PlayerCardDao
    @Autowired private lateinit var playerLandmarkDao: PlayerLandmarkDao
    @Autowired private lateinit var gameMarketplaceDao: GameMarketplaceDao

    private var gameId: Int = 0
    private var activePlayerId: Int = 0
    private var inactivePlayerId: Int = 0

    @BeforeEach
    fun setup() {
        transaction {
            PlayerCards.deleteAll()
            PlayerLandmarks.deleteAll()
            CardActivationNumbers.deleteAll()
            Players.deleteAll()
            GameMarketplace.deleteAll()
            Games.deleteAll()
            Users.deleteAll()
            Cards.deleteAll()
            Landmarks.deleteAll()
        }

        transaction {
            TestDataSeeder.seedAllCards()
            TestDataSeeder.seedAllLandmarks()

            val user1Id = (Users.insert { it[username] = "buyer1" } get Users.id).value
            val user2Id = (Users.insert { it[username] = "buyer2" } get Users.id).value

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

            playerDao.updateCoins(activePlayerId, 10)
            playerDao.updateCoins(inactivePlayerId, 5)

            gameMarketplaceDao.initForGame(gameId)

            playerLandmarkDao.initForPlayer(activePlayerId)
            playerLandmarkDao.initForPlayer(inactivePlayerId)
        }
    }

    // ── Happy-path: establishments ────────────────────────────────────────────

    @Test
    fun `establishment purchase updates coins ownership supply and purchase flag`() {
        purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)

        val player = playerDao.findById(activePlayerId)!!
        val ownedCard = playerCardDao.findByPlayerId(activePlayerId).single()
        val supply = gameMarketplaceDao.findByGameIdAndType(gameId, CardType.BAKERY)!!
        val game = gameDao.findById(gameId)!!

        assertEquals(9, player.coins)
        assertEquals(CardType.BAKERY, ownedCard.cardType)
        assertEquals(1, ownedCard.quantity)
        assertEquals(5, supply.quantityAvailable)
        assertTrue(game.hasPurchasedThisTurn)
    }

    @Test
    fun `inactive player coins are unaffected by opponent establishment purchase`() {
        purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)

        assertEquals(5, playerDao.findById(inactivePlayerId)!!.coins)
    }

    @Test
    fun `stadium purchase updates coins and supply`() {
        purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.STADIUM, null)

        val player = playerDao.findById(activePlayerId)!!
        val ownedCard = playerCardDao.findByPlayerId(activePlayerId)
            .first { it.cardType == CardType.STADIUM }
        val supply = gameMarketplaceDao.findByGameIdAndType(gameId, CardType.STADIUM)!!

        assertEquals(4, player.coins)
        assertEquals(1, ownedCard.quantity)
        assertEquals(5, supply.quantityAvailable)
    }

    // ── Happy-path: landmarks ─────────────────────────────────────────────────

    @Test
    fun `landmark purchase updates coins built state and purchase flag`() {
        purchaseService.purchase(gameId, PurchaseType.LANDMARK, null, LandmarkType.TRAIN_STATION)

        val player = playerDao.findById(activePlayerId)!!
        val landmark = playerLandmarkDao
            .findByPlayerIdAndType(activePlayerId, LandmarkType.TRAIN_STATION)!!
        val game = gameDao.findById(gameId)!!

        assertEquals(6, player.coins)
        assertTrue(landmark.isBuilt)
        assertTrue(game.hasPurchasedThisTurn)
    }

    @Test
    fun `inactive player landmarks are unaffected by opponent landmark purchase`() {
        purchaseService.purchase(gameId, PurchaseType.LANDMARK, null, LandmarkType.TRAIN_STATION)

        val inactiveLandmark = playerLandmarkDao
            .findByPlayerIdAndType(inactivePlayerId, LandmarkType.TRAIN_STATION)!!
        assertFalse(inactiveLandmark.isBuilt)
    }

    // ── Turn management ───────────────────────────────────────────────────────

    @Test
    fun `advanceTurn resets purchase flag for next turn`() {
        purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)
        gameDao.advanceTurn(gameId, nextTurnIndex = 1, roundNumber = 1)

        assertFalse(gameDao.findById(gameId)!!.hasPurchasedThisTurn)
    }

    // ── Earnings integration ──────────────────────────────────────────────────

    @Test
    fun `purchased establishment contributes to earnings in a later turn`() {
        purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)

        // cycle back to active player's turn
        gameDao.advanceTurn(gameId, nextTurnIndex = 1, roundNumber = 1)
        gameDao.advanceTurn(gameId, nextTurnIndex = 0, roundNumber = 2)
        gameDao.updateAfterRoll(gameId, diceRoll = 2, phase = TurnPhase.RESOLVE_EFFECTS)

        val coinsBefore = playerDao.findById(activePlayerId)!!.coins
        earningsService.resolveEffects(gameId)

        // bakery gives +1 from bank on own turn
        assertEquals(coinsBefore + 1, playerDao.findById(activePlayerId)!!.coins)
    }

    @Test
    fun `inactive player coins are unaffected during active player earnings resolution`() {
        purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)

        gameDao.advanceTurn(gameId, nextTurnIndex = 1, roundNumber = 1)
        gameDao.advanceTurn(gameId, nextTurnIndex = 0, roundNumber = 2)
        gameDao.updateAfterRoll(gameId, diceRoll = 2, phase = TurnPhase.RESOLVE_EFFECTS)

        val inactiveBefore = playerDao.findById(inactivePlayerId)!!.coins
        earningsService.resolveEffects(gameId)

        assertEquals(inactiveBefore, playerDao.findById(inactivePlayerId)!!.coins)
    }

    // ── Guard: phase enforcement ──────────────────────────────────────────────

    @Test
    fun `purchase outside buy or build phase does not mutate state`() {
        gameDao.updateTurnPhase(gameId, TurnPhase.RESOLVE_EFFECTS)
        val before = snapshot()

        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)
        }

        assertEquals("INVALID_TURN_PHASE", ex.errorCode)
        assertEquals(before, snapshot())
    }

    // ── Guard: one-purchase-per-turn ──────────────────────────────────────────

    @Test
    fun `second purchase in same turn is rejected without mutating state`() {
        purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)
        val before = snapshot()

        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(gameId, PurchaseType.LANDMARK, null, LandmarkType.TRAIN_STATION)
        }

        assertEquals("PURCHASE_ALREADY_MADE", ex.errorCode)
        assertEquals(before, snapshot())
    }

    // ── Guard: insufficient coins ─────────────────────────────────────────────

    @Test
    fun `establishment purchase with insufficient coins does not mutate state`() {
        playerDao.updateCoins(activePlayerId, 0)
        val before = snapshot()

        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)
        }

        assertEquals("INSUFFICIENT_COINS", ex.errorCode)
        assertEquals(before, snapshot())
    }

    @Test
    fun `landmark purchase with insufficient coins does not mutate state`() {
        playerDao.updateCoins(activePlayerId, 3)
        val before = snapshot()

        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(gameId, PurchaseType.LANDMARK, null, LandmarkType.TRAIN_STATION)
        }

        assertEquals("INSUFFICIENT_COINS", ex.errorCode)
        assertEquals(before, snapshot())
    }

    // ── Guard: missing entities ───────────────────────────────────────────────

    @Test
    fun `purchase fails when card does not exist`() {
        // Remove WHEAT_FIELD from DB to simulate missing card definition.
        // Marketplace and PlayerCards entries must be removed first due to FK constraints.
        transaction {
            GameMarketplace.deleteWhere { GameMarketplace.gameId eq gameId }
            Cards.deleteWhere { Cards.cardType eq CardType.WHEAT_FIELD }
        }
        val before = snapshot()

        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.WHEAT_FIELD, null)
        }

        assertEquals("CARD_NOT_FOUND", ex.errorCode)
        assertEquals(before, snapshot())
    }

    @Test
    fun `purchase fails when card is unavailable in marketplace`() {
        gameMarketplaceDao.updateQuantity(gameId, CardType.BAKERY, 0)
        val before = snapshot()

        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)
        }

        assertEquals("CARD_UNAVAILABLE", ex.errorCode)
        assertEquals(before, snapshot())
    }

    @Test
    fun `purchase fails when landmark does not exist`() {
        // Remove RADIO_TOWER from DB to simulate missing landmark definition.
        // PlayerLandmarks entries must be removed first due to FK constraints.
        transaction {
            val landmarkId = Landmarks.select(Landmarks.id)
                .where { Landmarks.landmarkType eq LandmarkType.RADIO_TOWER }
                .single()[Landmarks.id]
            PlayerLandmarks.deleteWhere { PlayerLandmarks.landmarkId eq landmarkId }
            Landmarks.deleteWhere { Landmarks.landmarkType eq LandmarkType.RADIO_TOWER }
        }
        val before = snapshot()

        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(gameId, PurchaseType.LANDMARK, null, LandmarkType.RADIO_TOWER)
        }

        assertEquals("LANDMARK_NOT_FOUND", ex.errorCode)
        assertEquals(before, snapshot())
    }

    @Test
    fun `purchase fails when active player cannot be resolved`() {
        transaction {
            Games.update({ Games.id eq gameId }) { it[currentTurnIndex] = 99 }
        }

        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)
        }

        assertEquals("ACTIVE_PLAYER_NOT_FOUND", ex.errorCode)
    }

    // ── Guard: duplicate landmark ─────────────────────────────────────────────

    @Test
    fun `building already built landmark is rejected without mutating state`() {
        purchaseService.purchase(gameId, PurchaseType.LANDMARK, null, LandmarkType.TRAIN_STATION)

        gameDao.advanceTurn(gameId, 1, 1)
        gameDao.advanceTurn(gameId, 0, 2)
        gameDao.updateTurnPhase(gameId, TurnPhase.BUY_OR_BUILD)

        val before = snapshot()

        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(gameId, PurchaseType.LANDMARK, null, LandmarkType.TRAIN_STATION)
        }

        assertEquals("LANDMARK_ALREADY_BUILT", ex.errorCode)
        assertEquals(before, snapshot())
    }

    // ── Guard: malformed requests ─────────────────────────────────────────────

    @Test
    fun `establishment purchase requires only card type`() {
        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, null, LandmarkType.TRAIN_STATION)
        }
        assertEquals("INVALID_PURCHASE_REQUEST", ex.errorCode)
    }

    @Test
    fun `establishment purchase rejects null card type`() {
        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, null, null)
        }
        assertEquals("INVALID_PURCHASE_REQUEST", ex.errorCode)
    }

    @Test
    fun `landmark purchase requires only landmark type`() {
        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(gameId, PurchaseType.LANDMARK, CardType.BAKERY, null)
        }
        assertEquals("INVALID_PURCHASE_REQUEST", ex.errorCode)
    }

    @Test
    fun `landmark purchase rejects null landmark type`() {
        val ex = assertThrows<CustomWebSocketException> {
            purchaseService.purchase(gameId, PurchaseType.LANDMARK, null, null)
        }
        assertEquals("INVALID_PURCHASE_REQUEST", ex.errorCode)
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    private fun snapshot() = PurchaseStateSnapshot(
        activeCoins = playerDao.findById(activePlayerId)!!.coins,
        inactiveCoins = playerDao.findById(inactivePlayerId)!!.coins,
        bakeryQuantity = playerCardDao.findByPlayerId(activePlayerId)
            .firstOrNull { it.cardType == CardType.BAKERY }?.quantity ?: 0,
        stadiumQuantity = playerCardDao.findByPlayerId(activePlayerId)
            .firstOrNull { it.cardType == CardType.STADIUM }?.quantity ?: 0,
        bakerySupply = gameMarketplaceDao
            .findByGameIdAndType(gameId, CardType.BAKERY)?.quantityAvailable ?: 0,
        stadiumSupply = gameMarketplaceDao
            .findByGameIdAndType(gameId, CardType.STADIUM)?.quantityAvailable ?: 0,
        trainStationBuilt = playerLandmarkDao
            .findByPlayerIdAndType(activePlayerId, LandmarkType.TRAIN_STATION)?.isBuilt ?: false,
        hasPurchasedThisTurn = gameDao.findById(gameId)!!.hasPurchasedThisTurn,
    )

    private data class PurchaseStateSnapshot(
        val activeCoins: Int,
        val inactiveCoins: Int,
        val bakeryQuantity: Int,
        val stadiumQuantity: Int,
        val bakerySupply: Int,
        val stadiumSupply: Int,
        val trainStationBuilt: Boolean,
        val hasPurchasedThisTurn: Boolean,
    )
}
