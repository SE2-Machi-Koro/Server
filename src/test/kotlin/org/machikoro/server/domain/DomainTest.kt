package org.machikoro.server.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.machikoro.server.domain.enums.CardColor
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.utils.LobbyCodeGenerator

class DomainTest {

    @Test
    fun `CardType has correct number of entries`() {
        assertEquals(15, CardType.entries.size)
    }

    @Test
    fun `CardType color is derived correctly`() {
        assertEquals(CardColor.BLUE, CardType.WHEAT_FIELD.color)
        assertEquals(CardColor.BLUE, CardType.RANCH.color)
        assertEquals(CardColor.GREEN, CardType.BAKERY.color)
        assertEquals(CardColor.RED, CardType.CAFE.color)
        assertEquals(CardColor.PURPLE, CardType.STADIUM.color)
    }

    @Test
    fun `CardType can be retrieved by name`() {
        assertEquals(CardType.WHEAT_FIELD, enumValueOf<CardType>("WHEAT_FIELD"))
    }

    @Test
    fun `LandmarkType has correct number of entries`() {
        assertEquals(4, LandmarkType.entries.size)
    }

    @Test
    fun `LandmarkType can be retrieved by name`() {
        assertEquals(LandmarkType.TRAIN_STATION, enumValueOf<LandmarkType>("TRAIN_STATION"))
    }

    @Test
    fun `GameStatus transitions cover all states`() {
        val statuses = GameStatus.entries
        assertTrue(statuses.contains(GameStatus.WAITING))
        assertTrue(statuses.contains(GameStatus.IN_PROGRESS))
        assertTrue(statuses.contains(GameStatus.FINISHED))
    }

    @Test
    fun `TurnPhase covers all phases in order`() {
        val phases = TurnPhase.entries
        assertEquals(TurnPhase.ROLL_DICE, phases[0])
        assertEquals(TurnPhase.RESOLVE_EFFECTS, phases[1])
        assertEquals(TurnPhase.BUY_OR_BUILD, phases[2])
        assertEquals(TurnPhase.END_TURN, phases[3])
    }

    @Test
    fun `CardColor has all four colors`() {
        val colors = CardColor.entries
        assertTrue(colors.contains(CardColor.BLUE))
        assertTrue(colors.contains(CardColor.GREEN))
        assertTrue(colors.contains(CardColor.RED))
        assertTrue(colors.contains(CardColor.PURPLE))
    }

    @Test
    fun `LobbyCodeGenerator generates valid code`() {
        repeat(100) {
            val code = LobbyCodeGenerator.generate()

            assertEquals(7, code.length)
            assertTrue(code.all { it.isUpperCase() || it.isDigit() })
        }
    }

    @Test
    fun `GameModel can represent a waiting lobby`() {
        val game = GameModel(
            id = 1,
            status = GameStatus.WAITING,
            hostUserId = 1,
            lobbyCode = "ABC1234",
            maxPlayers = 4,
            currentTurnIndex = 0,
            turnPhase = TurnPhase.ROLL_DICE,
            lastDiceRoll = null,
            hasPurchasedThisTurn = false,
            roundNumber = 1
        )

        assertEquals(GameStatus.WAITING, game.status)
        assertEquals("ABC1234", game.lobbyCode)
        assertEquals(4, game.maxPlayers)
    }
}
