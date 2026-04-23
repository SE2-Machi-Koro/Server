package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.DiceService
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.messaging.simp.SimpMessageHeaderAccessor

class WebSocketControllerTests {

    private val diceService: DiceService = mock(DiceService::class.java)
    private val controller = WebSocketController(diceService)

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
    fun rollDiceShouldReturnMessageWithRollResult() {
        val request = RollDiceRequest(gameId = 1, playerId = 2)
        val accessor = SimpMessageHeaderAccessor.create()
        `when`(diceService.rollDice(request)).thenReturn(5)

        val result = controller.rollDice(request, accessor)

        assertEquals(MessageType.ROLL_DICE, result.type)
        assertEquals("SERVER", result.sender)
        assertEquals(5, result.payload)
    }

}