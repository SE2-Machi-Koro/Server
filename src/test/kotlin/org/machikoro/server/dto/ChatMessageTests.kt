package org.machikoro.server.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatMessageTests {

    @Test
    fun messageTypeShouldExposeAllSupportedValues() {
        val expected = setOf(
            "CHAT", "JOIN", "LEAVE", "LOBBY_CREATED", "GAME_START", "GAME_STARTED", "SYNC", "GAME_ACTION", "GAME_END",
            "PLAYER_LEFT_FINISHED_GAME", "ERROR", "ROLL_DICE"
        )
        val actual = MessageType.entries.map { it.name }.toSet()

        assertEquals(expected, actual)
    }

    @Test
    fun webSocketMessageShouldUseExpectedDefaults() {
        val start = System.currentTimeMillis()
        val message = WebSocketMessage(type = MessageType.CHAT, sender = "alice")
        val end = System.currentTimeMillis()

        assertNull(message.content)
        assertNull(message.payload)
        assertTrue(message.timestamp in start..end)
    }

    @Test
    fun webSocketMessageShouldSupportCopyForImmutableUpdates() {
        val original = WebSocketMessage(type = MessageType.JOIN, sender = "bob", content = "joined")

        val updated = original.copy(type = MessageType.CHAT, content = "hello")

        assertEquals(MessageType.JOIN, original.type)
        assertEquals("joined", original.content)
        assertEquals(MessageType.CHAT, updated.type)
        assertEquals("hello", updated.content)
        assertEquals(original.sender, updated.sender)
    }
}

