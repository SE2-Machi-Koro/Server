package org.machikoro.server.dao

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import org.machikoro.server.domain.enums.PaymentSource
import org.machikoro.server.exception.CardNotFoundException

class PlayerCardDaoTest : AbstractDBSetup() {

    private val userDao = UserDao()
    private val gameDao = GameDao()
    private val playerDao = PlayerDao()
    private val playerCardDao = PlayerCardDao()

    private var playerId = 0

    @BeforeEach
    fun seed() {
        transaction {
            Cards.insertIgnore {
                it[cardType] = CardType.WHEAT_FIELD
                it[cost] = 1
                it[income] = 1
                it[color] = CardColor.BLUE
                it[establishmentType] = EstablishmentType.WHEAT
                it[paymentSource] = PaymentSource.BANK
            }
            Cards.insertIgnore {
                it[cardType] = CardType.RANCH
                it[cost] = 1
                it[income] = 1
                it[color] = CardColor.BLUE
                it[establishmentType] = EstablishmentType.COW
                it[paymentSource] = PaymentSource.BANK
            }
            Cards.insertIgnore {
                it[cardType] = CardType.FOREST
                it[cost] = 3
                it[income] = 1
                it[color] = CardColor.BLUE
                it[establishmentType] = EstablishmentType.GEAR
                it[paymentSource] = PaymentSource.BANK
            }
            Cards.insertIgnore {
                it[cardType] = CardType.MINE
                it[cost] = 6
                it[income] = 5
                it[color] = CardColor.BLUE
                it[establishmentType] = EstablishmentType.GEAR
                it[paymentSource] = PaymentSource.BANK
            }
            Cards.insertIgnore {
                it[cardType] = CardType.APPLE_ORCHARD
                it[cost] = 3
                it[income] = 3
                it[color] = CardColor.BLUE
                it[establishmentType] = EstablishmentType.WHEAT
                it[paymentSource] = PaymentSource.BANK
            }
            Cards.insertIgnore {
                it[cardType] = CardType.BAKERY
                it[cost] = 1
                it[income] = 1
                it[color] = CardColor.GREEN
                it[establishmentType] = EstablishmentType.BREAD
                it[paymentSource] = PaymentSource.BANK
            }
            Cards.insertIgnore {
                it[cardType] = CardType.CONVENIENCE_STORE
                it[cost] = 2
                it[income] = 3
                it[color] = CardColor.GREEN
                it[establishmentType] = EstablishmentType.BREAD
                it[paymentSource] = PaymentSource.BANK
            }
            Cards.insertIgnore {
                it[cardType] = CardType.CHEESE_FACTORY
                it[cost] = 5
                it[income] = 3
                it[color] = CardColor.GREEN
                it[establishmentType] = EstablishmentType.FACTORY
                it[paymentSource] = PaymentSource.BANK
            }
            Cards.insertIgnore {
                it[cardType] = CardType.FURNITURE_FACTORY
                it[cost] = 3
                it[income] = 3
                it[color] = CardColor.GREEN
                it[establishmentType] = EstablishmentType.FACTORY
                it[paymentSource] = PaymentSource.BANK
            }
            Cards.insertIgnore {
                it[cardType] = CardType.FRUIT_AND_VEGETABLE_MARKET
                it[cost] = 2
                it[income] = 2
                it[color] = CardColor.GREEN
                it[establishmentType] = EstablishmentType.FRUIT
                it[paymentSource] = PaymentSource.BANK
            }
            Cards.insertIgnore {
                it[cardType] = CardType.CAFE
                it[cost] = 2
                it[income] = 1
                it[color] = CardColor.RED
                it[establishmentType] = EstablishmentType.CUP
                it[paymentSource] = PaymentSource.ACTIVE_PLAYER
            }
            Cards.insertIgnore {
                it[cardType] = CardType.FAMILY_RESTAURANT
                it[cost] = 3
                it[income] = 2
                it[color] = CardColor.RED
                it[establishmentType] = EstablishmentType.CUP
                it[paymentSource] = PaymentSource.ACTIVE_PLAYER
            }
            Cards.insertIgnore {
                it[cardType] = CardType.STADIUM
                it[cost] = 6
                it[income] = 2
                it[color] = CardColor.PURPLE
                it[establishmentType] = EstablishmentType.MAJOR
                it[paymentSource] = PaymentSource.ALL_PLAYERS
            }
            Cards.insertIgnore {
                it[cardType] = CardType.TV_STATION
                it[cost] = 7
                it[income] = 0
                it[color] = CardColor.PURPLE
                it[establishmentType] = EstablishmentType.MAJOR
                it[paymentSource] = PaymentSource.CHOSEN_PLAYER
            }
        }
        val userId = userDao.create("card_user")
        val gameId = gameDao.create(userId)
        playerId = playerDao.addPlayer(gameId, userId).id
    }

    @AfterEach
    fun cleanup() {
        transaction {
            PlayerCards.deleteAll()
            CardActivationNumbers.deleteAll()
            Players.deleteAll()
            Games.deleteAll()
            Users.deleteAll()
            Cards.deleteAll()
        }
    }

    @Test
    fun `findByPlayerId returns empty list when player has no cards`() {
        assertTrue(playerCardDao.findByPlayerId(playerId).isEmpty())
    }

    @Test
    fun `findAll returns all player cards`() {
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 1)
        playerCardDao.upsert(playerId, CardType.BAKERY, 2)
        assertEquals(2, playerCardDao.findAll().size)
    }

    @Test
    fun `findAll returns empty list when no cards exist`() {
        assertTrue(playerCardDao.findAll().isEmpty())
    }

    @Test
    fun `upsert inserts new card entry`() {
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 1)
        val cards = playerCardDao.findByPlayerId(playerId)
        assertEquals(1, cards.size)
        assertEquals(CardType.WHEAT_FIELD, cards[0].cardType)
        assertEquals(1, cards[0].quantity)
    }

    @Test
    fun `upsert updates quantity for existing card`() {
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 1)
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 3)
        val cards = playerCardDao.findByPlayerId(playerId)
        assertEquals(1, cards.size)
        assertEquals(3, cards[0].quantity)
    }

    @Test
    fun `upsert with quantity zero deletes the row`() {
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 1)
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 0)
        assertTrue(playerCardDao.findByPlayerId(playerId).isEmpty())
    }

    @Test
    fun `upsert handles multiple different card types`() {
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 1)
        playerCardDao.upsert(playerId, CardType.BAKERY, 2)
        assertEquals(2, playerCardDao.findByPlayerId(playerId).size)
    }

    @Test
    fun `delete removes specific card from player`() {
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 1)
        playerCardDao.upsert(playerId, CardType.BAKERY, 1)
        playerCardDao.delete(playerId, CardType.WHEAT_FIELD)
        val remaining = playerCardDao.findByPlayerId(playerId)
        assertEquals(1, remaining.size)
        assertEquals(CardType.BAKERY, remaining[0].cardType)
    }

    @Test
    fun `upsert throws CardNotFoundException when card type does not exist in database`() {
        transaction { Cards.deleteAll() }
        assertThrows<CardNotFoundException> {
            playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 1)
        }
    }

    @Test
    fun `delete throws CardNotFoundException when card type does not exist in database`() {
        transaction { Cards.deleteAll() }
        assertThrows<CardNotFoundException> {
            playerCardDao.delete(playerId, CardType.CAFE)
        }
    }

    @Test
    fun `deleteAllByPlayerId removes all cards for the player`() {
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 1)
        playerCardDao.upsert(playerId, CardType.BAKERY, 2)

        playerCardDao.deleteAllByPlayerId(playerId)

        val remaining = playerCardDao.findByPlayerId(playerId)
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `deleteAllByPlayerId does not affect other players`() {
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 1)

        val userId2 = userDao.create("other_user")
        val gameId2 = gameDao.create(userId2)
        val otherPlayerId = playerDao.addPlayer(gameId2, userId2).id

        playerCardDao.upsert(otherPlayerId, CardType.CAFE, 3)

        playerCardDao.deleteAllByPlayerId(playerId)

        val remainingOther = playerCardDao.findByPlayerId(otherPlayerId)
        assertEquals(1, remainingOther.size)
        assertEquals(CardType.CAFE, remainingOther[0].cardType)
    }

    @Test
    fun `deleteAllByPlayerId on player with no cards does nothing`() {
        assertDoesNotThrow {
            playerCardDao.deleteAllByPlayerId(playerId)
        }

        assertTrue(playerCardDao.findByPlayerId(playerId).isEmpty())
    }
}