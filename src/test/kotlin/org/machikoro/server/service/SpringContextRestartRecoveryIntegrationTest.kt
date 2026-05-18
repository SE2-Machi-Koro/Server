package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.database.Cards
import org.machikoro.server.database.GameMarketplace
import org.machikoro.server.database.Games
import org.machikoro.server.database.Landmarks
import org.machikoro.server.database.PlayerCards
import org.machikoro.server.database.PlayerLandmarks
import org.machikoro.server.database.Players
import org.machikoro.server.database.TestDataSeeder
import org.machikoro.server.database.Users
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SpringContextRestartRecoveryIntegrationTest {

    @Autowired
    private lateinit var gameSyncService: GameSyncService

    @Autowired
    private lateinit var lobbyService: LobbyService

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

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.0").apply {
            withDatabaseName("machikoro_test")
            withUsername("test")
            withPassword("test")
        }

        @DynamicPropertySource
        @JvmStatic
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("machikoro.db.init.enabled") { "true" }
        }

        var sharedGameId: Int = 0
        var firstPlayerId: Int = 0
    }

    @Test
    @Order(1)
    @DirtiesContext
    fun `step 1 - setup in-progress game, mutate state, then simulate spring context restart`() {
        transaction {
            PlayerCards.deleteAll()
            PlayerLandmarks.deleteAll()
            Players.deleteAll()
            GameMarketplace.deleteAll()
            Games.deleteAll()
            Users.deleteAll()
            Landmarks.deleteAll()
            Cards.deleteAll()

            TestDataSeeder.seedAllCards()
            TestDataSeeder.seedAllLandmarks()
        }

        val hostUserId = transaction { Users.insert { it[Users.username] = "crashHost" } get Users.id }.value
        val guestUserId = transaction { Users.insert { it[Users.username] = "crashGuest" } get Users.id }.value

        sharedGameId = gameDao.create(hostUserId)
        
        // Add host and guest to the lobby
        lobbyService.addUserToLobby(sharedGameId, hostUserId)
        val gameCode = gameDao.findById(sharedGameId)!!.lobbyCode
        lobbyService.joinLobby(gameCode, guestUserId)
        
        lobbyService.startGame(sharedGameId)
        
        val players = playerDao.getPlayers(sharedGameId)
        firstPlayerId = players.first { it.userId == hostUserId }.id
        
        playerDao.updateCoins(firstPlayerId, 42)
        gameDao.advanceTurn(sharedGameId, nextTurnIndex = 1, roundNumber = 5)
        gameDao.updateAfterRoll(sharedGameId, diceRoll = 9, phase = TurnPhase.BUY_OR_BUILD)
        
        gameMarketplaceDao.decrementQuantity(sharedGameId, CardType.CAFE)
        playerCardDao.upsert(firstPlayerId, CardType.CAFE, quantity = 3)
        playerLandmarkDao.markBuilt(firstPlayerId, LandmarkType.AMUSEMENT_PARK)
    }

    @Test
    @Order(2)
    fun `step 2 - after context restart, game state is fully recovered from db`() {
        assertTrue(sharedGameId > 0, "Game ID must have survived JVM static scope")
        
        val snapshot = gameSyncService.buildSnapshot(sharedGameId)
        
        assertEquals(sharedGameId, snapshot.game.id)
        assertEquals(GameStatus.IN_PROGRESS, snapshot.game.status)
        assertEquals(TurnPhase.BUY_OR_BUILD, snapshot.game.turnPhase)
        assertEquals(9, snapshot.game.lastDiceRoll)
        assertEquals(5, snapshot.game.roundNumber)
        
        val firstPlayer = snapshot.players.first { it.id == firstPlayerId }
        assertEquals(42, firstPlayer.coins)
        
        val firstCards = snapshot.playerCards[firstPlayerId].orEmpty()
        val cafeCard = firstCards.find { it.cardType == CardType.CAFE }
        assertEquals(3, cafeCard?.quantity, "Player must retain purchased cards")
        
        val firstLandmarks = snapshot.playerLandmarks[firstPlayerId].orEmpty()
        val amusementPark = firstLandmarks.find { it.landmarkType == LandmarkType.AMUSEMENT_PARK }
        assertTrue(amusementPark?.isBuilt == true, "Landmark built status must survive restart")
        
        assertEquals(5, snapshot.marketplace[CardType.CAFE], "Marketplace quantities must survive restart")
        assertEquals(6, snapshot.marketplace[CardType.WHEAT_FIELD])
    }
}