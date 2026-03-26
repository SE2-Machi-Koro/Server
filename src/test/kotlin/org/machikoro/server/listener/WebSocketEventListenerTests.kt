package org.machikoro.server.listener

import org.junit.jupiter.api.Test
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
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
import kotlin.test.assertEquals

class WebSocketEventListenerTests {

    private val template = mock<SimpMessageSendingOperations>()
    private val listener = WebSocketEventListener(template)

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

    private fun disconnectEventWithSessionAttributes(attributes: Map<String, Any>): SessionDisconnectEvent {
        val accessor = SimpMessageHeaderAccessor.create(SimpMessageType.DISCONNECT)
        accessor.sessionAttributes = attributes.toMutableMap()
        val message: Message<ByteArray> = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)

        return SessionDisconnectEvent(this, message, "session-1", CloseStatus.NORMAL)
    }
}
