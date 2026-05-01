package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.GameMarketplace
import org.machikoro.server.database.Games
import org.machikoro.server.database.PlayerLandmarks
import org.machikoro.server.database.Players
import org.machikoro.server.database.Users
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.exception.GameNotFoundException
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

class LobbyServiceIntegrationTest : AbstractDBSetup() {

    @Autowired
    private lateinit var lobbyService: LobbyService

    @Autowired
    private lateinit var gameMarketplaceDao: GameMarketplaceDao

    @Autowired
    private lateinit var playerLandmarkDao: PlayerLandmarkDao

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
        }

        transaction {
            val user1Id = (Users.insert {
                it[username] = "lobbyHost"
            } get Users.id).value

            val user2Id = (Users.insert {
                it[username] = "lobbyGuest"
            } get Users.id).value

            gameId = (Games.insert {
                it[status] = GameStatus.WAITING
                it[hostUserId] = user1Id
                it[lobbyCode] = (1000000..9999999).random().toString()
                it[maxPlayers] = 4
                it[currentTurnIndex] = 0
                it[turnPhase] = org.machikoro.server.domain.enums.TurnPhase.ROLL_DICE
                it[lastDiceRoll] = null
                it[roundNumber] = 1
                it[hasPurchasedThisTurn] = false
            } get Games.id).value

            firstPlayerId = (Players.insert {
                it[Players.gameId] = gameId
                it[Players.userId] = user1Id
                it[turnOrder] = 0
                it[coins] = 3
            } get Players.id).value

            secondPlayerId = (Players.insert {
                it[Players.gameId] = gameId
                it[Players.userId] = user2Id
                it[turnOrder] = 1
                it[coins] = 3
            } get Players.id).value
        }
    }

    @Test
    fun `startGame sets status and initializes marketplace and landmark rows`() {
        val result = lobbyService.startGame(gameId)

        assertEquals(GameStatus.IN_PROGRESS, result.status)
        assertTrue(gameMarketplaceDao.findByGameId(gameId).isNotEmpty())
        assertTrue(playerLandmarkDao.findByPlayerId(firstPlayerId).isNotEmpty())
        assertTrue(playerLandmarkDao.findByPlayerId(secondPlayerId).isNotEmpty())
    }

    @Test
    fun `startGame throws GameNotFoundException for unknown game`() {
        assertThrows<GameNotFoundException> {
            lobbyService.startGame(999999)
        }
    }
}
