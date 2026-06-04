package org.machikoro.server.listener

import org.junit.jupiter.api.Test
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.PendingLobbyCreatedCache
import org.machikoro.server.service.WebSocketConnectionTracker
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.messaging.Message
import org.springframework.messaging.simp.SimpMessageSendingOperations
import org.springframework.messaging.simp.SimpMessageType
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import org.springframework.web.socket.messaging.SessionSubscribeEvent
import kotlin.test.assertEquals

class WebSocketEventListenerTests {

    private val template = mock<SimpMessageSendingOperations>()
    private val connectionTracker = mock<WebSocketConnectionTracker>()
    private val pendingLobbyCreatedCache = mock<PendingLobbyCreatedCache>()
    private val listener = WebSocketEventListener(template, connectionTracker, pendingLobbyCreatedCache)

    @Test
    fun handleWebSocketDisconnectListenerShouldPublishLeaveMessageWhenUsernameExists() {
        val event = disconnectEventWithSessionAttributes(mapOf("username" to "alice"))
        val payloadCaptor = argumentCaptor<WebSocketMessage>()

        listener.handleWebSocketDisconnectListener(event)

        verify(template).convertAndSend(eq("/topic/public"), payloadCaptor.capture())
        assertEquals(MessageType.LEAVE, payloadCaptor.firstValue.type)
        assertEquals("alice", payloadCaptor.firstValue.sender)
        assertEquals(null, payloadCaptor.firstValue.content)
    }

    @Test
    fun handleWebSocketDisconnectListenerShouldNotPublishWhenUsernameIsMissing() {
        val event = disconnectEventWithSessionAttributes(emptyMap())

        listener.handleWebSocketDisconnectListener(event)

        verify(template, never()).convertAndSend(eq("/topic/public"), any<WebSocketMessage>())
    }

    @Test
    fun handleWebSocketDisconnectListenerShouldUnregisterSession() {
        val event = disconnectEventWithSessionAttributes(mapOf("username" to "alice"))

        listener.handleWebSocketDisconnectListener(event)

        // session-1 is the id passed to SessionDisconnectEvent in the helper
        verify(connectionTracker).unregister("session-1")
    }

    @Test
    fun handleWebSocketDisconnectListenerShouldEvictPendingCacheEntry() {
        val event = disconnectEventWithSessionAttributes(mapOf("username" to "alice"))

        listener.handleWebSocketDisconnectListener(event)

        // Evict prevents the pending cache from leaking entries for sessions that never subscribed
        verify(pendingLobbyCreatedCache).evict("session-1")
    }

    @Test
    fun handleSubscribeEventShouldReplayPendingLobbyCreatedOnUserQueueSubscription() {
        val sessionId = "sess-42"
        val destination = "/queue/lobby-user$sessionId"
        val pending = WebSocketMessage(type = MessageType.LOBBY_CREATED, sender = "SERVER", content = "Lobby created", gameId = 1)

        org.mockito.kotlin.whenever(pendingLobbyCreatedCache.consume(sessionId)).thenReturn(pending)

        listener.handleSubscribeEvent(subscribeEvent(sessionId, destination))

        val destCaptor = argumentCaptor<String>()
        val msgCaptor = argumentCaptor<WebSocketMessage>()
        verify(template).convertAndSend(destCaptor.capture(), msgCaptor.capture())
        assertEquals(destination, destCaptor.firstValue)
        assertEquals(MessageType.LOBBY_CREATED, msgCaptor.firstValue.type)
        assertEquals(1, msgCaptor.firstValue.gameId)
    }

    @Test
    fun handleSubscribeEventShouldDoNothingWhenNoPendingMessage() {
        val sessionId = "sess-99"
        org.mockito.kotlin.whenever(pendingLobbyCreatedCache.consume(sessionId)).thenReturn(null)

        listener.handleSubscribeEvent(subscribeEvent(sessionId, "/queue/lobby-user$sessionId"))

        // No template send — nothing pending means no replay
        verify(template, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun handleSubscribeEventShouldIgnoreNonLobbyUserQueueDestinations() {
        // Subscribing to a game topic must not trigger replay even if there were a pending entry
        listener.handleSubscribeEvent(subscribeEvent("sess-5", "/topic/game/42"))

        verify(pendingLobbyCreatedCache, never()).consume(any())
        verify(template, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    private fun subscribeEvent(sessionId: String, destination: String): SessionSubscribeEvent {
        val accessor = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE)
        accessor.sessionId = sessionId
        accessor.destination = destination
        val message: Message<ByteArray> = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
        return SessionSubscribeEvent(this, message, null)
    }

    private fun disconnectEventWithSessionAttributes(attributes: Map<String, Any>): SessionDisconnectEvent {
        val accessor = SimpMessageHeaderAccessor.create(SimpMessageType.DISCONNECT)
        accessor.sessionId = "session-1"
        accessor.sessionAttributes = attributes.toMutableMap()
        val message: Message<ByteArray> = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)

        return SessionDisconnectEvent(this, message, "session-1", CloseStatus.NORMAL)
    }
}
