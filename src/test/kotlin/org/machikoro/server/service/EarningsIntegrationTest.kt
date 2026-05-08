package org.machikoro.server.service

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.CardActivationNumbers
import org.machikoro.server.database.Cards
import org.machikoro.server.database.Games
import org.machikoro.server.database.PlayerCards
import org.machikoro.server.database.Players
import org.machikoro.server.database.Users
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.PaymentSource
import org.machikoro.server.domain.enums.TriggerCondition
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.service.interfaces.EarningsService
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

class EarningsIntegrationTest : AbstractDBSetup() {

    @Autowired
    private lateinit var earningsService: EarningsService

    @Autowired
    private lateinit var gameDao: org.machikoro.server.dao.GameDao

    @Autowired
    private lateinit var playerDao: org.machikoro.server.dao.PlayerDao

    @Autowired
    private lateinit var playerCardDao: org.machikoro.server.dao.PlayerCardDao

    private var gameId: Int = 0
    private var player1Id: Int = 0
    private var player2Id: Int = 0

    @BeforeEach
    fun setup() {
        transaction {
            // Order matters for deletion due to foreign key constraints
            PlayerCards.deleteAll()
            CardActivationNumbers.deleteAll() // Must be deleted before Cards
            Players.deleteAll()
            Games.deleteAll()
            Users.deleteAll()
            Cards.deleteAll()
        }

        transaction {
            val wheatFieldId = Cards.insert {
                it[cardType] = CardType.WHEAT_FIELD
                it[cost] = 1
                it[income] = 1
                it[establishmentType] = EstablishmentType.WHEAT
                it[triggerCondition] = TriggerCondition.ANY_TURN
                it[paymentSource] = PaymentSource.BANK
            } get Cards.id

            CardActivationNumbers.insert {
                it[cardId] = wheatFieldId
                it[number] = 1
            }

            val bakeryId = Cards.insert {
                it[cardType] = CardType.BAKERY
                it[cost] = 1
                it[income] = 1
                it[establishmentType] = EstablishmentType.BREAD
                it[triggerCondition] = TriggerCondition.OWN_TURN
                it[paymentSource] = PaymentSource.BANK
            } get Cards.id

            CardActivationNumbers.insert {
                it[cardId] = bakeryId
                it[number] = 2
            }
            CardActivationNumbers.insert {
                it[cardId] = bakeryId
                it[number] = 3
            }

            val cafeId = Cards.insert {
                it[cardType] = CardType.CAFE
                it[cost] = 2
                it[income] = 1
                it[establishmentType] = EstablishmentType.CUP
                it[triggerCondition] = TriggerCondition.OTHER_TURN
                it[paymentSource] = PaymentSource.ACTIVE_PLAYER
            } get Cards.id

            CardActivationNumbers.insert {
                it[cardId] = cafeId
                it[number] = 3
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
                it[lobbyCode] = (1000000..9999999).random().toString()
                it[maxPlayers] = 4
                it[currentTurnIndex] = 0
                it[turnPhase] = TurnPhase.RESOLVE_EFFECTS
                it[lastDiceRoll] = 1
                it[roundNumber] = 1
            } get Games.id).value

            player1Id = playerDao.addPlayer(gameId, user1Id).id
            player2Id = playerDao.addPlayer(gameId, user2Id).id

            playerCardDao.upsert(player1Id, CardType.WHEAT_FIELD, 2)
            playerCardDao.upsert(player2Id, CardType.WHEAT_FIELD, 1)
            playerCardDao.upsert(player2Id, CardType.CAFE, 1)
        }
    }

    @Test
    fun `resolveEffects distributes correct coins and transitions phase`() {
        // 1. Update the game to roll a 3
        transaction {
            Games.update({ Games.id eq gameId }) {
                it[lastDiceRoll] = 3
            }
        }

        earningsService.resolveEffects(gameId)

        val p1 = playerDao.findById(player1Id)!!
        val p2 = playerDao.findById(player2Id)!!

        // Logic for roll 3:
        // P2's Cafe triggers first: P1 pays P2 1 coin (P1=2, P2=4)
        // P1's Bakery triggers: P1 gets 1 coin from bank (P1=3, P2=4)
        // Note: Wheat fields don't trigger on a 3
        assertEquals(3, p1.coins)
        assertEquals(4, p2.coins)
    }

    @Test
    fun `green card only pays active player`() {
        transaction {
            Games.deleteAll()
            PlayerCards.deleteAll()
            Players.deleteAll()
        }

        transaction {
            val user1Id = (Users.insert {
                it[username] = "p1green"
            } get Users.id).value

            val user2Id = (Users.insert {
                it[username] = "p2green"
            } get Users.id).value

            val gId = (Games.insert {
                it[status] = GameStatus.IN_PROGRESS
                it[hostUserId] = user1Id
                it[lobbyCode] = (1000000..9999999).random().toString()
                it[maxPlayers] = 4
                it[currentTurnIndex] = 0
                it[turnPhase] = TurnPhase.RESOLVE_EFFECTS
                it[lastDiceRoll] = 2
                it[roundNumber] = 1
            } get Games.id).value

            val p1 = playerDao.addPlayer(gId, user1Id).id
            val p2 = playerDao.addPlayer(gId, user2Id).id

            playerCardDao.upsert(p1, CardType.BAKERY, 1)
            playerCardDao.upsert(p2, CardType.BAKERY, 1)

            earningsService.resolveEffects(gId)

            val rp1 = playerDao.findById(p1)!!
            val rp2 = playerDao.findById(p2)!!

            assertEquals(4, rp1.coins)
            assertEquals(3, rp2.coins)
        }
    }

    @Test
    fun `red card triggers before blue and green cards`() {
        // Setup: P1 (Active) has 1 coin. P2 has Cafe (Red, Roll 3). P1 has Bakery (Green, Roll 3).
        transaction {
            Players.update({ Players.id eq player1Id }) { it[coins] = 1 }
            Games.update({ Games.id eq gameId }) { it[lastDiceRoll] = 3 }
        }

        earningsService.resolveEffects(gameId)

        val p1 = playerDao.findById(player1Id)!!
        val p2 = playerDao.findById(player2Id)!!

        // 1. Red triggers: P1 pays P2 his only 1 coin. (P1=0, P2=4)
        // 2. Green triggers: P1 gets 1 coin from bank. (P1=1, P2=4)
        assertEquals(1, p1.coins)
        assertEquals(4, p2.coins)
    }
}