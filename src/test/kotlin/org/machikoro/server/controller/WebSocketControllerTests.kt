package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.UserDao
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.dto.RollDiceResponse
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.DiceService
import org.machikoro.server.service.LobbyService
import org.mockito.Mockito.`when`
import org.mockito.kotlin.mock
import org.springframework.messaging.simp.SimpMessageHeaderAccessor

class WebSocketControllerTests {

    private val diceService = mock<DiceService>()
    private val lobbyService = mock<LobbyService>()
    private val userDao = mock<UserDao>()
    private val controller = WebSocketController(diceService, lobbyService, userDao)

    @Test
    fun sendMessageShouldReturnUnchangedMessage() {
        val message = WebSocketMessage(
            type = MessageType.CHAT,
            sender = "alice",
            content = "Hello"
        )

        val result = controller.sendMessage(message)

        assertEquals(message, result)
    }

    @Test
    fun addUserShouldStoreUsernameInSessionAttributes() {
        val message = WebSocketMessage(type = MessageType.JOIN, sender = "bob")
        val accessor = SimpMessageHeaderAccessor.create()
        accessor.sessionAttributes = mutableMapOf()

        val result = controller.addUser(message, accessor)

        assertEquals(message, result)
        assertEquals("bob", accessor.sessionAttributes?.get("username"))
    }

    @Test
    fun addUserShouldNotFailWhenSessionAttributesAreMissing() {
        val message = WebSocketMessage(type = MessageType.JOIN, sender = "carol")
        val accessor = SimpMessageHeaderAccessor.create()

        val result = controller.addUser(message, accessor)

        assertEquals(message, result)
        assertNull(accessor.sessionAttributes)
    }

    @Test
    fun rollDiceShouldReturnWebSocketMessageWithDiceResult() {
        val request = RollDiceRequest(gameId = 1, playerId = 2)
        val accessor = SimpMessageHeaderAccessor.create()
        val rollDiceResponse = RollDiceResponse(dice = listOf(3, 4), total = 7)

        `when`(diceService.rollDice(request)).thenReturn(rollDiceResponse)

        val result = controller.rollDice(request, accessor)

        assertEquals(MessageType.ROLL_DICE, result.type)
        assertEquals("SERVER", result.sender)
        assertEquals("Player 2 rolled: 7", result.content)
        assertEquals(mapOf("dice" to listOf(3, 4), "total" to 7), result.payload)
    }
}