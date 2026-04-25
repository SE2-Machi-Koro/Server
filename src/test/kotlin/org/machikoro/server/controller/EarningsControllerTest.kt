package org.machikoro.server.controller

import org.junit.jupiter.api.Test
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.ResolveEffectsRequest
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.service.interfaces.EarningsService
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessagingTemplate
import kotlin.test.assertEquals

class EarningsControllerTest {

    private val earningsService: EarningsService = mock()
    private val messagingTemplate: SimpMessagingTemplate = mock()
    private val controller = EarningsController(earningsService, messagingTemplate)

    @Test
    fun `resolveEffects calls service and broadcasts success`() {
        val request = ResolveEffectsRequest(gameId = 1)

        controller.resolveEffects(request)

        verify(earningsService).resolveEffects(1)

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/public"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_ACTION, message.type)
        assertEquals("server", message.sender)
        assertEquals("EFFECTS_RESOLVED", (message.payload as Map<*, *>)["event"])
        assertEquals(1, (message.payload as Map<*, *>)["gameId"])
    }

    @Test
    fun `resolveEffects does not broadcast when service throws`() {
        val request = ResolveEffectsRequest(gameId = 1)
        doThrow(GameNotFoundException("Game 1 not found")).whenever(earningsService).resolveEffects(1)

        controller.resolveEffects(request)

        verify(messagingTemplate, org.mockito.kotlin.never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }
}