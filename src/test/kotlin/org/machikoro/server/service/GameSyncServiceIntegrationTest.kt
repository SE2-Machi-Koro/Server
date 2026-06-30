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
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

/**
 * Locks in the full /app/game.sync reconnect contract: every field of the
 * returned [org.machikoro.server.dto.GameStateDto] must reflect the persisted
 * state mid-game. Future regressions on any individual field surface here.
 *
 * Sibling to [LobbyServiceIntegrationTest] — same Testcontainers/Postgres
 * setup, same fixture style.
 */
class GameSyncServiceIntegrationTest : AbstractDBSetup() {

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

    private var gameId: Int = 0
    private var firstPlayerId: Int = 0
    private var secondPlayerId: Int = 0
    private var firstUserId: Int = 0
    private var secondUserId: Int = 0

    @BeforeEach
    fun setup() {
        transaction {
            PlayerCards.deleteAll()
            PlayerLandmarks.deleteAll()
            Players.deleteAll()
            GameMarketplace.deleteAll()
            Games.deleteAll()
            Users.deleteAll()
            Landmarks.deleteAll()
            Cards.deleteAll()
        }

        val userIds = transaction {
            TestDataSeeder.seedAllCards()
            TestDataSeeder.seedAllLandmarks()

            val user1Id = (Users.insert { it[username] = "syncHost" } get Users.id).value
            val user2Id = (Users.insert { it[username] = "syncGuest" } get Users.id).value
            user1Id to user2Id
        }

        gameId = gameDao.create(userIds.first)
        firstUserId = userIds.first
        secondUserId = userIds.second
        firstPlayerId = playerDao.addPlayer(gameId, userIds.first).id
        secondPlayerId = playerDao.addPlayer(gameId, userIds.second).id
    }

    @Test
    fun `buildSnapshot reflects every field of the persisted game state`() {
        // Flip to IN_PROGRESS and seed marketplace + landmarks via the same
        // path the real startGame flow uses, so this test exercises the
        // production seeding logic rather than a hand-rolled fixture.
        lobbyService.startGame(gameId)

        playerDao.updateCoins(firstPlayerId, 10)
        playerDao.updateCoins(secondPlayerId, 7)

        // advanceTurn resets turnPhase to ROLL_DICE, clears lastDiceRoll, and
        // clears hasPurchasedThisTurn — so apply it FIRST, then layer the
        // roll / phase / purchase state on top.
        gameDao.advanceTurn(gameId, nextTurnIndex = 1, roundNumber = 3)
        gameDao.tryRecordDiceRoll(gameId, diceRoll = 8, diceCount = 2)
        gameDao.updateTurnPhase(gameId, TurnPhase.BUY_OR_BUILD)
        gameDao.updateHasPurchasedThisTurn(gameId, hasPurchasedThisTurn = true)

        // Simulate the first player buying a BAKERY: decrement marketplace,
        // insert the player card.
        gameMarketplaceDao.decrementQuantity(gameId, CardType.BAKERY)
        playerCardDao.upsert(firstPlayerId, CardType.BAKERY, quantity = 1)

        // Build one of two landmarks on the first player.
        playerLandmarkDao.markBuilt(firstPlayerId, LandmarkType.TRAIN_STATION)

        val snapshot = gameSyncService.buildSnapshot(gameId)

        // ── game fields ─────────────────────────────────────────────────
        assertEquals(gameId, snapshot.game.id)
        assertEquals(GameStatus.IN_PROGRESS, snapshot.game.status)
        assertEquals(TurnPhase.BUY_OR_BUILD, snapshot.game.turnPhase)
        assertEquals(8, snapshot.game.lastDiceRoll)
        assertEquals(3, snapshot.game.roundNumber)
        assertEquals(1, snapshot.game.currentTurnIndex)
        assertTrue(snapshot.game.hasPurchasedThisTurn)

        // ── players ─────────────────────────────────────────────────────
        val firstPlayer = snapshot.players.first { it.id == firstPlayerId }
        val secondPlayer = snapshot.players.first { it.id == secondPlayerId }
        assertEquals(10, firstPlayer.coins)
        assertEquals(7, secondPlayer.coins)

        // ── playerCards ─────────────────────────────────────────────────
        // Both players start with WHEAT_FIELD + BAKERY from initialization.
        // The extra upsert(BAKERY, 1) above keeps BAKERY quantity at 1 (upsert is idempotent here).
        val firstCards = snapshot.playerCards[firstPlayerId].orEmpty()
        assertEquals(2, firstCards.size)
        val firstCardTypes = firstCards.map { it.cardType }.toSet()
        assertEquals(setOf(CardType.WHEAT_FIELD, CardType.BAKERY), firstCardTypes)
        assertEquals(1, firstCards.first { it.cardType == CardType.BAKERY }.quantity)

        val secondCards = snapshot.playerCards[secondPlayerId].orEmpty()
        assertEquals(2, secondCards.size)
        assertEquals(setOf(CardType.WHEAT_FIELD, CardType.BAKERY), secondCards.map { it.cardType }.toSet())

        // ── playerLandmarks ─────────────────────────────────────────────
        // All 4 landmark types are initialized. First player has TRAIN_STATION built;
        // rest are unbuilt. Second player has all 4 unbuilt.
        val firstLandmarks = snapshot.playerLandmarks[firstPlayerId].orEmpty()
        val firstTrain = firstLandmarks.find { it.landmarkType == LandmarkType.TRAIN_STATION }
        val firstMall = firstLandmarks.find { it.landmarkType == LandmarkType.SHOPPING_MALL }
        assertTrue(
            requireNotNull(firstTrain) { "TRAIN_STATION row missing for first player" }.isBuilt,
        )
        assertFalse(
            requireNotNull(firstMall) { "SHOPPING_MALL row missing for first player" }.isBuilt,
        )

        val secondLandmarks = snapshot.playerLandmarks[secondPlayerId].orEmpty()
        assertEquals(4, secondLandmarks.size)
        assertTrue(secondLandmarks.all { !it.isBuilt })

        // ── marketplace ─────────────────────────────────────────────────
        // BAKERY decremented by 1 (default 6 → 5), WHEAT_FIELD still at 6.
        assertEquals(5, snapshot.marketplace[CardType.BAKERY])
        assertEquals(6, snapshot.marketplace[CardType.WHEAT_FIELD])

        // ── turnOrder ───────────────────────────────────────────────────
        // turnOrder carries user IDs — same ID space as activePlayerId.
        assertEquals(2, snapshot.turnOrder.size)
        assertTrue(snapshot.turnOrder.containsAll(listOf(firstUserId, secondUserId)))
    }
}
