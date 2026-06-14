package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.SyncGameRequest
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.exception.GameStartedException
import org.machikoro.server.service.GameSyncService
import org.machikoro.server.service.LobbyService
import org.machikoro.server.service.WebSocketConnectionTracker
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate

class WebSocketControllerTests {

    private val lobbyService = mock<LobbyService>()
    private val connectionTracker = mock<WebSocketConnectionTracker>()
    private val gameSyncService = mock<GameSyncService>()
    private val messagingTemplate = mock<SimpMessagingTemplate>()
    private val controller = WebSocketController(
        lobbyService,
        connectionTracker,
        gameSyncService,
        messagingTemplate,
    )

    /** Helper: create an accessor pre-populated with an authenticated UserPrincipal. */
    private fun authenticatedAccessor(
        userId: Int,
        username: String,
        sessionId: String = "session-default",
    ): SimpMessageHeaderAccessor = SimpMessageHeaderAccessor.create().apply {
        this.sessionId = sessionId
        this.sessionAttributes = mutableMapOf()
        user = UserPrincipal(userId = userId, username = username)
    }

    @BeforeEach
    fun setup() {
        whenever(gameSyncService.findActiveInProgressGameId(any())).thenReturn(null)
    }

    @Test
    fun sendMessageShouldReturnUnchangedMessage() {
        val message = WebSocketMessage(type = MessageType.CHAT, sender = "alice", content = "Hello")

        val result = controller.sendMessage(message)

        assertEquals(message, result)
    }

    @Test
    fun addUserShouldStoreUsernameInSessionAttributes() {
        val message = WebSocketMessage(type = MessageType.JOIN, sender = "ignored-payload-sender")
        val accessor = authenticatedAccessor(userId = 1, username = "bob")

        val result = controller.addUser(message, accessor)

        assertEquals(message, result)
        // Username stored must come from the authenticated principal, not from message.sender
        assertEquals("bob", accessor.sessionAttributes?.get("username"))
    }

    @Test
    fun addUserShouldNotFailWhenSessionAttributesAreMissing() {
        val message = WebSocketMessage(type = MessageType.JOIN, sender = "carol")
        // A valid principal but no sessionAttributes map
        val accessor = SimpMessageHeaderAccessor.create().apply {
            user = UserPrincipal(userId = 2, username = "carol")
        }

        val result = controller.addUser(message, accessor)

        assertEquals(message, result)
        assertNull(accessor.sessionAttributes)
    }

    @Test
    fun `addUser returns early without touching lobby when principal is missing`() {
        val message = WebSocketMessage(type = MessageType.JOIN, sender = "ghost", gameId = 1)
        // No principal set — simulates a connection that bypassed STOMP auth (shouldn't happen in prod)
        val accessor = SimpMessageHeaderAccessor.create().apply {
            sessionAttributes = mutableMapOf()
        }

        controller.addUser(message, accessor)

        verify(lobbyService, never()).addUserToLobby(any(), any())
        verify(connectionTracker, never()).register(any(), any(), any())
    }

    @Test
    fun `addUser ignores stale client gameId when no active server game exists`() {
        val gameId = 1
        val accessor = authenticatedAccessor(userId = 10, username = "dave", sessionId = "session-abc")

        val message = WebSocketMessage(type = MessageType.JOIN, sender = "dave", gameId = gameId)

        controller.addUser(message, accessor)

        verify(lobbyService, never()).addUserToLobby(any(), any())
        verify(connectionTracker, never()).register(any(), any(), any())
    }

    @Test
    fun `addUser does not call addUserToLobby when gameId is null and no active game`() {
        val accessor = authenticatedAccessor(userId = 5, username = "eve")

        val message = WebSocketMessage(type = MessageType.JOIN, sender = "eve", gameId = null)

        controller.addUser(message, accessor)

        verify(lobbyService, never()).addUserToLobby(any(), any())
        verify(connectionTracker, never()).register(any(), any(), any())
    }

    @Test
    fun `addUser does not register session when sessionId is null`() {
        val message = WebSocketMessage(type = MessageType.JOIN, sender = "frank", gameId = 2)
        whenever(gameSyncService.findActiveInProgressGameId(7)).thenReturn(2)
        // accessor without sessionId set → sessionId is null
        val accessor = SimpMessageHeaderAccessor.create().apply {
            sessionAttributes = mutableMapOf()
            user = UserPrincipal(userId = 7, username = "frank")
        }

        controller.addUser(message, accessor)

        verify(lobbyService).addUserToLobby(2, 7)
        verify(connectionTracker, never()).register(any(), any(), any())
    }

    @Test
    fun `addUser maps reconnecting user to active in-progress game and sends SYNC`() {
        whenever(gameSyncService.findActiveInProgressGameId(11)).thenReturn(7)
        whenever(gameSyncService.buildSnapshot(7)).thenReturn(mock<GameStateDto>())

        val accessor = authenticatedAccessor(userId = 11, username = "gina", sessionId = "session-rejoin")
        val message = WebSocketMessage(type = MessageType.JOIN, sender = "gina", gameId = null)

        controller.addUser(message, accessor)

        verify(lobbyService).addUserToLobby(7, 11)
        verify(connectionTracker).register("session-rejoin", 11, 7)

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(
            eq("/queue/game-sync-usersession-rejoin"),
            captor.capture(),
        )
        assertEquals(MessageType.SYNC, captor.firstValue.type)
        assertEquals(7, captor.firstValue.gameId)
        @Suppress("UNCHECKED_CAST")
        val payload = captor.firstValue.payload as Map<String, Any>
        assertEquals(11, payload["targetUserId"])
        assertEquals("session-rejoin", payload["targetSessionId"])
    }

    @Test
    fun `addUser handles transition race by re-mapping user and syncing state`() {
        whenever(gameSyncService.findActiveInProgressGameId(12)).thenReturn(3, 3, 3)
        whenever(lobbyService.addUserToLobby(3, 12)).thenThrow(GameStartedException("already started"))
        whenever(gameSyncService.buildSnapshot(3)).thenReturn(mock<GameStateDto>())

        val accessor = authenticatedAccessor(userId = 12, username = "harry", sessionId = "session-race")
        val message = WebSocketMessage(type = MessageType.JOIN, sender = "harry", gameId = 999)

        controller.addUser(message, accessor)

        verify(connectionTracker).register("session-race", 12, 3)
        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(
            eq("/queue/game-sync-usersession-race"),
            captor.capture(),
        )
        assertEquals(MessageType.SYNC, captor.firstValue.type)
        @Suppress("UNCHECKED_CAST")
        val payload = captor.firstValue.payload as Map<String, Any>
        assertEquals(12, payload["targetUserId"])
        assertEquals("session-race", payload["targetSessionId"])
    }

    @Test
    fun `syncGameState publishes SYNC for connected player with active game`() {
        whenever(gameSyncService.findActiveInProgressGameId(50)).thenReturn(8)
        whenever(gameSyncService.isInProgress(8)).thenReturn(true)
        whenever(gameSyncService.buildSnapshot(8)).thenReturn(mock<GameStateDto>())

        val accessor = authenticatedAccessor(userId = 50, username = "sync-user", sessionId = "session-sync")

        controller.syncGameState(SyncGameRequest(gameId = null), accessor)

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(
            eq("/queue/game-sync-usersession-sync"),
            captor.capture(),
        )
        assertEquals(MessageType.SYNC, captor.firstValue.type)
        assertEquals(8, captor.firstValue.gameId)
        @Suppress("UNCHECKED_CAST")
        val payload = captor.firstValue.payload as Map<String, Any>
        assertEquals(50, payload["targetUserId"])
        assertEquals("session-sync", payload["targetSessionId"])
    }

    @Test
    fun `syncGameState does nothing when principal is missing`() {
        // No UserPrincipal on the accessor — should reject silently
        val accessor = SimpMessageHeaderAccessor.create().apply {
            sessionId = "session-no-principal"
        }

        controller.syncGameState(SyncGameRequest(gameId = 1), accessor)

        verify(messagingTemplate, never()).convertAndSend(
            any<String>(),
            any<WebSocketMessage>(),
        )
    }

    @Test
    fun `syncGameState rejects explicit gameId when user is not a member`() {
        whenever(gameSyncService.isUserInGame(70, 99)).thenReturn(false)

        val accessor = authenticatedAccessor(userId = 70, username = "unauthorized-user", sessionId = "session-unauthorized")

        controller.syncGameState(SyncGameRequest(gameId = 99), accessor)

        verify(messagingTemplate, never()).convertAndSend(
            any<String>(),
            any<WebSocketMessage>(),
        )
        verify(gameSyncService, never()).buildSnapshot(any())
    }
}
