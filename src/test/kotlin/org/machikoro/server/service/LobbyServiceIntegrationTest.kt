package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.LandmarkDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.CardActivationNumbers
import org.machikoro.server.database.Cards
import org.machikoro.server.database.GameMarketplace
import org.machikoro.server.database.Games
import org.machikoro.server.database.Landmarks
import org.machikoro.server.database.PlayerLandmarks
import org.machikoro.server.database.Players
import org.machikoro.server.database.Users
import org.machikoro.server.domain.enums.CardColor
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.PaymentSource
import org.machikoro.server.exception.GameNotFoundException
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

class LobbyServiceIntegrationTest : AbstractDBSetup() {

    @Autowired
    private lateinit var lobbyService: LobbyService

    @Autowired
    private lateinit var gameDao: GameDao

    @Autowired
    private lateinit var playerDao: PlayerDao

    @Autowired
    private lateinit var gameMarketplaceDao: GameMarketplaceDao

    @Autowired
    private lateinit var playerLandmarkDao: PlayerLandmarkDao

    @Autowired
    private lateinit var cardDao: CardDao

    @Autowired
    private lateinit var landmarkDao: LandmarkDao

    private var gameId: Int = 0
    private var firstPlayerId: Int = 0
    private var secondPlayerId: Int = 0

    @BeforeEach
    fun setup() {
        transaction {
            PlayerLandmarks.deleteAll()
            Players.deleteAll()
            GameMarketplace.deleteAll()
            Games.deleteAll()
            Users.deleteAll()
            CardActivationNumbers.deleteAll()
            Landmarks.deleteAll()
            Cards.deleteAll()
        }

        val userIds = transaction {
            val bakeryId = Cards.insertIgnore {
                it[cardType] = CardType.BAKERY
                it[cost] = 1
                it[income] = 1
                it[color] = CardColor.GREEN
                it[establishmentType] = EstablishmentType.BREAD
                it[paymentSource] = PaymentSource.BANK
            } get Cards.id

            CardActivationNumbers.insertIgnore {
                it[cardId] = bakeryId
                it[number] = 2
            }
            CardActivationNumbers.insertIgnore {
                it[cardId] = bakeryId
                it[number] = 3
            }

            Landmarks.insertIgnore {
                it[landmarkType] = LandmarkType.TRAIN_STATION
                it[cost] = 4
            }

            val user1Id = (Users.insert {
                it[username] = "lobbyHost"
            } get Users.id).value

            val user2Id = (Users.insert {
                it[username] = "lobbyGuest"
            } get Users.id).value

            user1Id to user2Id
        }

        gameId = gameDao.create(userIds.first)
        firstPlayerId = playerDao.addPlayer(gameId, userIds.first).id
        secondPlayerId = playerDao.addPlayer(gameId, userIds.second).id
    }

    @Test
    fun `startGame sets status and initializes marketplace and landmark rows`() {
        val result = lobbyService.startGame(gameId)

        assertEquals(GameStatus.IN_PROGRESS, result.game.status)
        assertTrue(gameMarketplaceDao.findByGameId(gameId).isNotEmpty())
        assertTrue(playerLandmarkDao.findByPlayerId(firstPlayerId).isNotEmpty())
        assertTrue(playerLandmarkDao.findByPlayerId(secondPlayerId).isNotEmpty())
        // Snapshot must include the freshly-initialized landmarks so the
        // client renders the build grid without an extra round-trip — see
        // SE2-Machi-Koro/Server#247.
        assertTrue(result.playerLandmarks[firstPlayerId]?.isNotEmpty() == true)
        assertTrue(result.playerLandmarks[secondPlayerId]?.isNotEmpty() == true)
        // All landmarks start unbuilt at game start.
        assertTrue(result.playerLandmarks.values.flatten().all { !it.isBuilt })
        // Marketplace must also ride along on the initial snapshot — same
        // motivation as landmarks, this time for #248. Only BAKERY is seeded
        // in the Cards table by setup(), so initForGame skips the other types
        // (cardId lookup misses) and only BAKERY shows up at the default supply.
        assertEquals(6, result.marketplace[CardType.BAKERY])
        val bakeryDefinition = result.cardDefinitions.single { it.cardType == CardType.BAKERY }
        assertEquals(1, bakeryDefinition.cost)
        assertEquals(CardColor.GREEN, bakeryDefinition.color)
        assertEquals(listOf(2, 3), bakeryDefinition.activationNumbers.sorted())
        val landmarkDefinition = result.landmarkDefinitions.single { it.landmarkType == LandmarkType.TRAIN_STATION }
        assertEquals(4, landmarkDefinition.cost)
    }

    @Test
    fun `startGame throws GameNotFoundException for unknown game`() {
        assertThrows<GameNotFoundException> {
            lobbyService.startGame(999999)
        }
    }

    @Test
    fun `startGame rolls back setup when landmark initialization fails`() {
        val failingLandmarkDao = mock<PlayerLandmarkDao> {
            on { initForPlayer(any()) } doThrow RuntimeException("landmark setup failed")
        }

        val service = LobbyService(
            gameDao,
            playerDao,
            gameMarketplaceDao,
            failingLandmarkDao,
            cardDao,
            landmarkDao,
        )

        assertThrows<RuntimeException> {
            service.startGame(gameId)
        }

        assertEquals(GameStatus.WAITING, gameDao.findById(gameId)!!.status)
        assertTrue(gameMarketplaceDao.findByGameId(gameId).isEmpty())
        assertTrue(playerLandmarkDao.findByPlayerId(firstPlayerId).isEmpty())
        assertTrue(playerLandmarkDao.findByPlayerId(secondPlayerId).isEmpty())
    }
}
