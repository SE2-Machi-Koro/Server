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
import org.machikoro.server.database.Cards
import org.machikoro.server.database.Games
import org.machikoro.server.database.PlayerCards
import org.machikoro.server.database.Players
import org.machikoro.server.database.Users
import org.machikoro.server.domain.enums.CardType
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
                it[Cards.cardType] = CardType.WHEAT_FIELD; it[Cards.cost] = 1; it[Cards.diceMin] =
                1; it[Cards.diceMax] = 1; it[Cards.income] = 1
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.RANCH; it[Cards.cost] = 1; it[Cards.diceMin] = 2; it[Cards.diceMax] =
                2; it[Cards.income] = 1
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.FOREST; it[Cards.cost] = 3; it[Cards.diceMin] = 5; it[Cards.diceMax] =
                5; it[Cards.income] = 1
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.MINE; it[Cards.cost] = 6; it[Cards.diceMin] = 9; it[Cards.diceMax] =
                9; it[Cards.income] = 5
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.APPLE_ORCHARD; it[Cards.cost] = 3; it[Cards.diceMin] =
                10; it[Cards.diceMax] = 10; it[Cards.income] = 3
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.BAKERY; it[Cards.cost] = 1; it[Cards.diceMin] = 2; it[Cards.diceMax] =
                3; it[Cards.income] = 1
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.CONVENIENCE_STORE; it[Cards.cost] = 2; it[Cards.diceMin] =
                4; it[Cards.diceMax] = 4; it[Cards.income] = 3
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.CHEESE_FACTORY; it[Cards.cost] = 5; it[Cards.diceMin] =
                7; it[Cards.diceMax] = 7; it[Cards.income] = 3
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.FURNITURE_FACTORY; it[Cards.cost] = 3; it[Cards.diceMin] =
                8; it[Cards.diceMax] = 8; it[Cards.income] = 3
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.FRUIT_AND_VEGETABLE_MARKET; it[Cards.cost] = 2; it[Cards.diceMin] =
                11; it[Cards.diceMax] = 11; it[Cards.income] = 2
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.CAFE; it[Cards.cost] = 2; it[Cards.diceMin] = 3; it[Cards.diceMax] =
                3; it[Cards.income] = 1
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.FAMILY_RESTAURANT; it[Cards.cost] = 3; it[Cards.diceMin] =
                9; it[Cards.diceMax] = 10; it[Cards.income] = 2
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.STADIUM; it[Cards.cost] = 6; it[Cards.diceMin] = 6; it[Cards.diceMax] =
                6; it[Cards.income] = 2
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.TV_STATION; it[Cards.cost] = 7; it[Cards.diceMin] = 6; it[Cards.diceMax] =
                6; it[Cards.income] = 0
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.BUSINESS_CENTER; it[Cards.cost] = 8; it[Cards.diceMin] =
                6; it[Cards.diceMax] = 6; it[Cards.income] = 0
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
        // current player
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 1)

        // second player
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
}
