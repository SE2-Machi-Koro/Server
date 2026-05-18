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
import org.machikoro.server.database.AbstractDBSetup
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
import org.springframework.test.annotation.DirtiesContext

/**
 * Validates Sprint 2 requirement: Game state recovery upon failure/restart of the backend container.
 *
 * This test spins up the Testcontainers Postgres database, initializes an in-progress game,
 * mutates its state (purchasing, building), and then uses `@DirtiesContext` to force Spring
 * to completely destroy and recreate the ApplicationContext (simulating a backend container restart).
 * The second test verifies that the re-issued /app/game.sync snapshot accurately recovers
 * the full state from the surviving Postgres container.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BackendRestartRecoveryIntegrationTest : AbstractDBSetup() {

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
        // Survives the Spring Context restart so step 2 can query the same game
        var sharedGameId: Int = 0
        var firstPlayerId: Int = 0
    }

    @Test
    @Order(1)
    @DirtiesContext
    fun `step 1 - setup in-progress game, mutate state, then crash backend`() {
        // 1. Clean DB (safe because Testcontainers is dedicated to this test suite)
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

        // 2. Setup game and players
        val hostUserId = transaction { Users.insert { it[username] = "crashHost" } get Users.id }.value
        val guestUserId = transaction { Users.insert { it[username] = "crashGuest" } get Users.id }.value

        sharedGameId = gameDao.create(hostUserId)
        val gameCode = gameDao.getGame(sharedGameId).lobbyCode
        lobbyService.joinLobby(gameCode, guestUserId)
        
        // 3. Start game to initialize phase, marketplace, and landmarks
        lobbyService.startGame(sharedGameId)
        
        val players = playerDao.getPlayers(sharedGameId)
        firstPlayerId = players.first { it.userId == hostUserId }.id
        
        // 4. Mutate state drastically so we know it's not just defaults surviving
        playerDao.updateCoins(firstPlayerId, 42)
        gameDao.advanceTurn(sharedGameId, nextTurnIndex = 1, roundNumber = 5)
        gameDao.updateAfterRoll(sharedGameId, diceRoll = 9, phase = TurnPhase.BUY_OR_BUILD)
        
        // Simulate purchase & build
        gameMarketplaceDao.decrementQuantity(sharedGameId, CardType.CAFE)
        playerCardDao.upsert(firstPlayerId, CardType.CAFE, quantity = 3)
        playerLandmarkDao.markBuilt(firstPlayerId, LandmarkType.AMUSEMENT_PARK)

        // The method ends here, and @DirtiesContext triggers the context teardown,
        // simulating a SIGTERM / container restart of the backend application.
    }

    @Test
    @Order(2)
    fun `step 2 - after context restart, game state is fully recovered from db`() {
        // Assert that state passed across the JVM static boundary
        assertTrue(sharedGameId > 0, "Game ID must have survived JVM static scope")
        
        // Simulates the client calling /app/game.sync upon WebSocket reconnect
        val snapshot = gameSyncService.buildSnapshot(sharedGameId)
        
        // Verify state is completely intact
        assertEquals(sharedGameId, snapshot.game.id)
        assertEquals(GameStatus.IN_PROGRESS, snapshot.game.status)
        assertEquals(TurnPhase.BUY_OR_BUILD, snapshot.game.turnPhase)
        assertEquals(9, snapshot.game.lastDiceRoll)
        assertEquals(5, snapshot.game.roundNumber)
        
        // Verify coins
        val firstPlayer = snapshot.players.first { it.id == firstPlayerId }
        assertEquals(42, firstPlayer.coins)
        
        // Verify cards
        val firstCards = snapshot.playerCards[firstPlayerId].orEmpty()
        val cafeCard = firstCards.find { it.cardType == CardType.CAFE }
        assertEquals(3, cafeCard?.quantity, "Player must retain purchased cards")
        
        // Verify landmarks
        val firstLandmarks = snapshot.playerLandmarks[firstPlayerId].orEmpty()
        val amusementPark = firstLandmarks.find { it.landmarkType == LandmarkType.AMUSEMENT_PARK }
        assertTrue(amusementPark?.isBuilt == true, "Landmark built status must survive restart")
        
        // Verify marketplace
        assertEquals(5, snapshot.marketplace[CardType.CAFE], "Marketplace quantities must survive restart")
        assertEquals(6, snapshot.marketplace[CardType.WHEAT_FIELD])
    }
}