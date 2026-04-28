package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

class PurchaseServiceIntegrationTest : AbstractDBSetup() {

    @Autowired
    private lateinit var purchaseService: PurchaseService

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

            // Updated for new PlayerDao API
            activePlayerId = playerDao.addPlayer(gameId, user1Id).id
            playerDao.addPlayer(gameId, user2Id)

            // Override default starting coins (3) for test setup
            playerDao.updateCoins(activePlayerId, 6)
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
}