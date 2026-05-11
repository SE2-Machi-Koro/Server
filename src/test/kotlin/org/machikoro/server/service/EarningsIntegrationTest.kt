package org.machikoro.server.service

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.CardActivationNumbers
import org.machikoro.server.database.Cards
import org.machikoro.server.database.Games
import org.machikoro.server.database.PlayerCards
import org.machikoro.server.database.Players
import org.machikoro.server.database.Users
import org.machikoro.server.domain.enums.CardColor
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.PaymentSource
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.exception.GameNotFoundException
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
            PlayerCards.deleteAll()
            CardActivationNumbers.deleteAll()
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
                it[color] = CardColor.BLUE
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
                it[color] = CardColor.GREEN
                it[establishmentType] = EstablishmentType.BREAD
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
                it[color] = CardColor.RED
                it[establishmentType] = EstablishmentType.CUP
                it[paymentSource] = PaymentSource.ACTIVE_PLAYER
            } get Cards.id

            CardActivationNumbers.insert {
                it[cardId] = cafeId
                it[number] = 3
            }

            val stadiumId = Cards.insert {
                it[cardType] = CardType.STADIUM
                it[cost] = 6
                it[income] = 2
                it[color] = CardColor.PURPLE
                it[establishmentType] = EstablishmentType.MAJOR
                it[paymentSource] = PaymentSource.ALL_PLAYERS
            } get Cards.id

            CardActivationNumbers.insert {
                it[cardId] = stadiumId
                it[number] = 6
            }

            val tvStationId = Cards.insert {
                it[cardType] = CardType.TV_STATION
                it[cost] = 7
                it[income] = 5
                it[color] = CardColor.PURPLE
                it[establishmentType] = EstablishmentType.MAJOR
                it[paymentSource] = PaymentSource.CHOSEN_PLAYER
            } get Cards.id

            CardActivationNumbers.insert {
                it[cardId] = tvStationId
                it[number] = 6
            }

            val businessCenterId = Cards.insert {
                it[cardType] = CardType.BUSINESS_CENTER
                it[cost] = 8
                it[income] = 0
                it[color] = CardColor.PURPLE
                it[establishmentType] = EstablishmentType.MAJOR
                it[paymentSource] = PaymentSource.NONE
            } get Cards.id

            CardActivationNumbers.insert {
                it[cardId] = businessCenterId
                it[number] = 6
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

            // P1: Wheat Field x2
            // P2: Wheat Field x1, Cafe x1
            playerCardDao.upsert(player1Id, CardType.WHEAT_FIELD, 2)
            playerCardDao.upsert(player2Id, CardType.WHEAT_FIELD, 1)
            playerCardDao.upsert(player2Id, CardType.CAFE, 1)
        }
    }

    // --- resolveEffects guard branches ---

    @Test
    fun `resolveEffects throws GameNotFoundException for unknown game`() {
        assertThrows<GameNotFoundException> {
            earningsService.resolveEffects(999999)
        }
    }

    @Test
    fun `resolveEffects throws IllegalStateException when phase is not RESOLVE_EFFECTS`() {
        transaction {
            Games.update({ Games.id eq gameId }) { it[turnPhase] = TurnPhase.BUY_OR_BUILD }
        }

        assertThrows<IllegalStateException> {
            earningsService.resolveEffects(gameId)
        }
    }

    @Test
    fun `resolveEffects throws IllegalStateException when lastDiceRoll is null`() {
        transaction {
            Games.update({ Games.id eq gameId }) { it[lastDiceRoll] = null }
        }

        assertThrows<IllegalStateException> {
            earningsService.resolveEffects(gameId)
        }
    }

    // --- card color activation rules ---

    @Test
    fun `blue card pays all players regardless of whose turn it is`() {
        // Roll 1: Wheat Field (Blue) activates for every player on any turn.
        // P1 owns 2x -> +2, P2 owns 1x -> +1
        earningsService.resolveEffects(gameId)

        assertEquals(5, playerDao.findById(player1Id)!!.coins)
        assertEquals(4, playerDao.findById(player2Id)!!.coins)
    }

    @Test
    fun `green card only pays active player`() {
        transaction {
            Games.update({ Games.id eq gameId }) { it[lastDiceRoll] = 2 }
            playerCardDao.upsert(player1Id, CardType.BAKERY, 1)
            playerCardDao.upsert(player2Id, CardType.BAKERY, 1)
        }

        // Roll 2: Bakery (Green) — only active player (P1, currentTurnIndex=0) gets paid.
        // Roll 2 does not activate Wheat Field, so only Bakery matters here.
        // P1: 3 + 1 = 4, P2: 3 (no activation)
        earningsService.resolveEffects(gameId)

        assertEquals(4, playerDao.findById(player1Id)!!.coins)
        assertEquals(3, playerDao.findById(player2Id)!!.coins)
    }

    @Test
    fun `red card only activates on opponents turn and steals from active player`() {
        transaction {
            Games.update({ Games.id eq gameId }) { it[lastDiceRoll] = 3 }
        }

        // Roll 3: Cafe (Red) owned by P2 triggers only because P1 is active.
        // P1: 3 - 1 = 2, P2: 3 + 1 = 4
        earningsService.resolveEffects(gameId)

        assertEquals(2, playerDao.findById(player1Id)!!.coins)
        assertEquals(4, playerDao.findById(player2Id)!!.coins)
    }

    @Test
    fun `red card clamps active player coins to zero when they cannot cover the payment`() {
        transaction {
            Players.update({ Players.id eq player1Id }) { it[coins] = 0 }
            Games.update({ Games.id eq gameId }) { it[lastDiceRoll] = 3 }
        }

        // P1 has 0 coins — delta would be -1 but clamp keeps it at 0.
        // P2 still receives the full income.
        earningsService.resolveEffects(gameId)

        assertEquals(0, playerDao.findById(player1Id)!!.coins)
        assertEquals(4, playerDao.findById(player2Id)!!.coins)
    }

    // --- PaymentSource branches ---

    @Test
    fun `ALL_PLAYERS payment source deducts from every other player and pays card owner`() {
        transaction {
            Games.update({ Games.id eq gameId }) { it[lastDiceRoll] = 6 }
            // P1 owns Stadium (Purple, ALL_PLAYERS, income=2, activates on 6)
            // With 2 players, perPlayerAmount = 2 / (2-1) = 2
            playerCardDao.upsert(player1Id, CardType.STADIUM, 1)
        }

        // Roll 6: Stadium (Purple) activates only on active player's turn (P1).
        // P2 contributes 2 coins to P1.
        // P1: 3 + 2 = 5, P2: 3 - 2 = 1
        earningsService.resolveEffects(gameId)

        assertEquals(5, playerDao.findById(player1Id)!!.coins)
        assertEquals(1, playerDao.findById(player2Id)!!.coins)
    }

    @Test
    fun `CHOSEN_PLAYER payment source does not automatically move coins`() {
        transaction {
            Games.update({ Games.id eq gameId }) { it[lastDiceRoll] = 6 }
            playerCardDao.upsert(player1Id, CardType.TV_STATION, 1)
        }

        // Roll 6: TV Station (Purple, CHOSEN_PLAYER) — no automatic payment.
        // Coins unchanged for both players.
        earningsService.resolveEffects(gameId)

        assertEquals(3, playerDao.findById(player1Id)!!.coins)
        assertEquals(3, playerDao.findById(player2Id)!!.coins)
    }

    @Test
    fun `NONE payment source does not move any coins`() {
        transaction {
            Games.update({ Games.id eq gameId }) { it[lastDiceRoll] = 6 }
            playerCardDao.upsert(player1Id, CardType.BUSINESS_CENTER, 1)
        }

        // Roll 6: Business Center (Purple, NONE, income=0) — no payment of any kind.
        // Coins unchanged for both players.
        earningsService.resolveEffects(gameId)

        assertEquals(3, playerDao.findById(player1Id)!!.coins)
        assertEquals(3, playerDao.findById(player2Id)!!.coins)
    }

    @Test
    fun `card with zero income does not update coins`() {
        transaction {
            Games.update({ Games.id eq gameId }) { it[lastDiceRoll] = 6 }
            // Business Center has income=0, so totalEarnings <= 0 guard fires.
            playerCardDao.upsert(player1Id, CardType.BUSINESS_CENTER, 1)
        }

        earningsService.resolveEffects(gameId)

        // No delta applied — both players keep their starting coins.
        assertEquals(3, playerDao.findById(player1Id)!!.coins)
        assertEquals(3, playerDao.findById(player2Id)!!.coins)
    }

    @Test
    fun `processEarnings called directly distributes coins without phase transition`() {
        // Directly exercise the public processEarnings method.
        // Roll 1: Wheat Field (Blue), P1 owns 2x, P2 owns 1x.
        earningsService.processEarnings(gameId, diceRoll = 1, activePlayerId = player1Id)

        assertEquals(5, playerDao.findById(player1Id)!!.coins)
        assertEquals(4, playerDao.findById(player2Id)!!.coins)

        // Phase must remain unchanged since processEarnings does not touch it.
        val game = gameDao.findById(gameId)!!
        assertEquals(TurnPhase.RESOLVE_EFFECTS, game.turnPhase)
    }

    @Test
    fun `processEarningsInTransaction throws GameNotFoundException when active player not in game`() {
        assertThrows<GameNotFoundException> {
            earningsService.processEarnings(gameId, diceRoll = 1, activePlayerId = 999999)
        }
    }
}