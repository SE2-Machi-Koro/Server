package org.machikoro.server.listener

import org.junit.jupiter.api.Test
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.messaging.Message
import org.springframework.messaging.simp.SimpMessageSendingOperations
import org.springframework.messaging.simp.SimpMessageType
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import kotlin.test.assertEquals

class WebSocketEventListenerTests {

    private val template = mock(SimpMessageSendingOperations::class.java)
    private val listener = WebSocketEventListener(template)

    @Test
    fun handleWebSocketDisconnectListenerShouldPublishLeaveMessageWhenUsernameExists() {
        val event = disconnectEventWithSessionAttributes(mapOf("username" to "alice"))
        val payloadCaptor = ArgumentCaptor.forClass(WebSocketMessage::class.java)

        listener.handleWebSocketDisconnectListener(event)

        verify(template).convertAndSend(eq("/topic/public"), payloadCaptor.capture())
        assertEquals(MessageType.LEAVE, payloadCaptor.value.type)
        assertEquals("alice", payloadCaptor.value.sender)
        assertEquals(null, payloadCaptor.value.content)
    }

    @Test
    fun handleWebSocketDisconnectListenerShouldNotPublishWhenUsernameIsMissing() {
        val event = disconnectEventWithSessionAttributes(emptyMap())

        listener.handleWebSocketDisconnectListener(event)

        verify(template, never()).convertAndSend(eq("/topic/public"), org.mockito.Mockito.any(WebSocketMessage::class.java))
    }

    private fun disconnectEventWithSessionAttributes(attributes: Map<String, Any>): SessionDisconnectEvent {
        val accessor = SimpMessageHeaderAccessor.create(SimpMessageType.DISCONNECT)
        accessor.sessionAttributes = attributes.toMutableMap()
        val message: Message<ByteArray> = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)

        return SessionDisconnectEvent(this, message, "session-1", CloseStatus.NORMAL)
    }
}


