package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.Cards
import org.machikoro.server.database.Games
import org.machikoro.server.database.PlayerCards
import org.machikoro.server.database.Players
import org.machikoro.server.database.Users
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.service.interfaces.EarningsService
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

class EarningsIntegrationTest : AbstractDBSetup() {

    @Autowired
    private lateinit var earningsService: EarningsService

    @Autowired
    private lateinit var gameDao: GameDao

    @Autowired
    private lateinit var playerDao: PlayerDao

    @Autowired
    private lateinit var playerCardDao: PlayerCardDao

    private var gameId: Int = 0
    private var player1Id: Int = 0
    private var player2Id: Int = 0

    @BeforeEach
    fun setup() {
        transaction {
            PlayerCards.deleteAll()
            Players.deleteAll()
            Games.deleteAll()
            Users.deleteAll()
            Cards.deleteAll()
        }

        transaction {
            Cards.insert {
                it[cardType] = CardType.WHEAT_FIELD
                it[name] = "Wheat Field"
                it[cost] = 1
                it[diceMin] = 1
                it[diceMax] = 1
                it[income] = 1
            }
            Cards.insert {
                it[cardType] = CardType.RANCH
                it[name] = "Ranch"
                it[cost] = 1
                it[diceMin] = 2
                it[diceMax] = 2
                it[income] = 1
            }

            val user1Id = (Users.insert {
                it[username] = "player1"
            } get Users.id).value

            val user2Id = (Users.insert {
                it[username] = "player2"
            } get Users.id).value

            gameId = (Games.insert {
                it[status] = GameStatus.IN_PROGRESS
                it[hostUserId] = user1Id
                it[currentTurnIndex] = 0
                it[turnPhase] = TurnPhase.RESOLVE_EFFECTS
                it[lastDiceRoll] = 1
                it[roundNumber] = 1
            } get Games.id).value

            player1Id = playerDao.create(gameId, user1Id, 0)
            player2Id = playerDao.create(gameId, user2Id, 1)

            playerCardDao.upsert(player1Id, CardType.WHEAT_FIELD, 2)
            playerCardDao.upsert(player2Id, CardType.WHEAT_FIELD, 1)
            playerCardDao.upsert(player2Id, CardType.RANCH, 1)
        }
    }

    @Test
    fun `resolveEffects distributes correct coins and transitions phase`() {
        earningsService.resolveEffects(gameId)

        val p1 = playerDao.findById(player1Id)!!
        val p2 = playerDao.findById(player2Id)!!
        val game = gameDao.findById(gameId)!!

        assertEquals(5, p1.coins)
        assertEquals(4, p2.coins)
        assertEquals(TurnPhase.BUY_OR_BUILD, game.turnPhase)
    }
}