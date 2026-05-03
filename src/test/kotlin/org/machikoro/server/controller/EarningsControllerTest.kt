package org.machikoro.server.controller

import java.security.Principal
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.ResolveEffectsRequest
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.service.GameStateGuard
import org.machikoro.server.service.interfaces.EarningsService
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessagingTemplate
import kotlin.test.assertEquals

class EarningsControllerTest {

    private val earningsService: EarningsService = mock()
    private val messagingTemplate: SimpMessagingTemplate = mock()
    private val gameStateGuard: GameStateGuard = mock()
    private val controller = EarningsController(earningsService, messagingTemplate, gameStateGuard)

    private val principal: Principal = UserPrincipal(userId = 1, username = "alice")

    @Test
    fun `resolveEffects calls service and broadcasts success`() {
        val request = ResolveEffectsRequest(gameId = 1)

        controller.resolveEffects(request, principal)

        verify(gameStateGuard).ensureSenderIsActivePlayer(1, principal)
        verify(earningsService).resolveEffects(1)

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/1"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_ACTION, message.type)
        assertEquals("server", message.sender)
        assertEquals("EFFECTS_RESOLVED", (message.payload as Map<*, *>)["event"])
        assertEquals(1, (message.payload as Map<*, *>)["gameId"])
    }

    @Test
    fun `resolveEffects broadcasts error when service throws`() {
        val request = ResolveEffectsRequest(gameId = 1)
        doThrow(GameNotFoundException("Game 1 not found")).whenever(earningsService).resolveEffects(1)

        controller.resolveEffects(request, principal)

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/1"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.ERROR, message.type)
        assertEquals("server", message.sender)
        assertEquals("EFFECTS_FAILED", (message.payload as Map<*, *>)["event"])
        assertEquals("Game 1 not found", (message.payload as Map<*, *>)["message"])
    }

    @Test
    fun `resolveEffects propagates NOT_YOUR_TURN and does not call service or broadcast`() {
        val request = ResolveEffectsRequest(gameId = 1)
        whenever(gameStateGuard.ensureSenderIsActivePlayer(1, principal))
            .thenThrow(CustomWebSocketException("NOT_YOUR_TURN", "It is not your turn"))

        val ex = assertThrows<CustomWebSocketException> {
            controller.resolveEffects(request, principal)
        }
        assertEquals("NOT_YOUR_TURN", ex.errorCode)
        verify(earningsService, never()).resolveEffects(any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }
}
