package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WebSocketConnectionTrackerTest {

    private lateinit var tracker: WebSocketConnectionTracker

    @BeforeEach
    fun setUp() {
        tracker = WebSocketConnectionTracker()
    }

    @Test
    fun `register makes userId visible via getConnectedUserIds for the correct game`() {
        tracker.register("session-1", userId = 10, gameId = 1)

        assertEquals(setOf(10), tracker.getConnectedUserIds(gameId = 1))
    }

    @Test
    fun `getConnectedUserIds returns empty set when no sessions exist for game`() {
        assertTrue(tracker.getConnectedUserIds(gameId = 99).isEmpty())
    }

    @Test
    fun `getConnectedUserIds excludes users belonging to a different game`() {
        tracker.register("session-a", userId = 1, gameId = 1)
        tracker.register("session-b", userId = 2, gameId = 2)

        assertEquals(setOf(1), tracker.getConnectedUserIds(gameId = 1))
    }

    @Test
    fun `getConnectedUserIds returns all connected users for the game`() {
        tracker.register("session-a", userId = 1, gameId = 5)
        tracker.register("session-b", userId = 2, gameId = 5)
        tracker.register("session-c", userId = 3, gameId = 5)

        assertEquals(setOf(1, 2, 3), tracker.getConnectedUserIds(gameId = 5))
    }

    @Test
    fun `unregister removes the session so userId is no longer returned`() {
        tracker.register("session-1", userId = 10, gameId = 1)
        tracker.unregister("session-1")

        assertTrue(tracker.getConnectedUserIds(gameId = 1).isEmpty())
    }

    @Test
    fun `unregister of unknown session does not throw`() {
        tracker.unregister("nonexistent-session")
    }

    @Test
    fun `register with same sessionId overwrites the previous entry`() {
        tracker.register("session-1", userId = 10, gameId = 1)
        tracker.register("session-1", userId = 20, gameId = 2)

        assertTrue(tracker.getConnectedUserIds(gameId = 1).isEmpty())
        assertEquals(setOf(20), tracker.getConnectedUserIds(gameId = 2))
    }
}

