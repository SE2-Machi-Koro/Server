package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
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
import org.machikoro.server.domain.enums.CardColor
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.PaymentSource
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
            // Two card types so we can exercise both the marketplace decrement
            // (BAKERY purchased) and the unaffected-supply (WHEAT_FIELD) paths.
            Cards.insertIgnore {
                it[cardType] = CardType.BAKERY
                it[cost] = 1
                it[income] = 1
                it[color] = CardColor.GREEN
                it[establishmentType] = EstablishmentType.BREAD
                it[paymentSource] = PaymentSource.BANK
            }
            Cards.insertIgnore {
                it[cardType] = CardType.WHEAT_FIELD
                it[cost] = 1
                it[income] = 1
                it[color] = CardColor.BLUE
                it[establishmentType] = EstablishmentType.WHEAT
                it[paymentSource] = PaymentSource.BANK
            }

            // Two landmarks so we can assert one built vs one unbuilt on the
            // same player without conflating "no row" with "unbuilt row".
            Landmarks.insertIgnore {
                it[landmarkType] = LandmarkType.TRAIN_STATION
                it[cost] = 4
            }
            Landmarks.insertIgnore {
                it[landmarkType] = LandmarkType.SHOPPING_MALL
                it[cost] = 10
            }

            val user1Id = (Users.insert { it[username] = "syncHost" } get Users.id).value
            val user2Id = (Users.insert { it[username] = "syncGuest" } get Users.id).value
            user1Id to user2Id
        }

        gameId = gameDao.create(userIds.first)
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
        gameDao.updateAfterRoll(gameId, diceRoll = 8, phase = TurnPhase.BUY_OR_BUILD)
        gameDao.updateHasPurchasedThisTurn(gameId, hasPurchasedThisTurn = true)

        // Simulate the first player buying a BAKERY: decrement marketplace,
        // insert the player card.
        gameMarketplaceDao.decrementQuantity(gameId, CardType.BAKERY)
        playerCardDao.upsert(firstPlayerId, CardType.BAKERY, quantity = 2)

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
        // First player owns two BAKERYs and one WHEAT_FIELD;
        val firstCards = snapshot.playerCards[firstPlayerId].orEmpty()
        assertEquals(2, firstCards.size)
        assertEquals(CardType.BAKERY, firstCards[0].cardType)
        assertEquals(2, firstCards[0].quantity)
        assertEquals(CardType.WHEAT_FIELD, firstCards[1].cardType)
        assertEquals(1, firstCards[1].quantity)
        //second player owns one BAKERY and one WHEAT_FILED.
        val secondCards = snapshot.playerCards[secondPlayerId].orEmpty()
        assertEquals(2, secondCards.size)
        assertEquals(CardType.BAKERY, secondCards[0].cardType)
        assertEquals(1, secondCards[0].quantity)
        assertEquals(CardType.WHEAT_FIELD, secondCards[1].cardType)
        assertEquals(1, secondCards[1].quantity)


        // ── playerLandmarks ─────────────────────────────────────────────
        // First player: TRAIN_STATION built, SHOPPING_MALL unbuilt.
        // Second player: both unbuilt.
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
        assertEquals(2, secondLandmarks.size)
        assertTrue(secondLandmarks.all { !it.isBuilt })

        // ── marketplace ─────────────────────────────────────────────────
        // BAKERY decremented by 1 (default 6 → 5), WHEAT_FIELD still at 6.
        // Only the two card types seeded by setup() show up — initForGame
        // skips types whose Cards row isn't present.
        assertEquals(5, snapshot.marketplace[CardType.BAKERY])
        assertEquals(6, snapshot.marketplace[CardType.WHEAT_FIELD])

        // ── turnOrder ───────────────────────────────────────────────────
        // Both players present; ordering itself is randomized by startGame.
        assertEquals(2, snapshot.turnOrder.size)
        assertTrue(snapshot.turnOrder.containsAll(listOf(firstPlayerId, secondPlayerId)))
    }
}
