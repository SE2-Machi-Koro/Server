package org.machikoro.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.domain.models.UserModel
import org.machikoro.server.dto.GameChatRequest
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.DiceService
import org.machikoro.server.service.GameEndBroadcaster
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.GameStateGuard
import org.machikoro.server.service.GameSyncService
import org.machikoro.server.service.LobbyService
import org.machikoro.server.service.PurchaseService
import org.machikoro.server.service.AccusationService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.util.MimeTypeUtils
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.messaging.converter.MappingJackson2MessageConverter
import java.lang.reflect.Type
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.docker.compose.enabled=false",
        "spring.flyway.enabled=false",
        "machikoro.db.init.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GameChatControllerTest {
    private companion object {
        private const val MESSAGE_TIMEOUT_SECONDS = 5L
        private const val BROKER_SUBSCRIPTION_TIMEOUT_SECONDS = 10L
        private const val BROKER_SUBSCRIPTION_PROBE_WAIT_MILLIS = 250L
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var messagingTemplate: SimpMessagingTemplate

    private val activeSessions = mutableListOf<StompSession>()

    @MockitoBean
    private lateinit var userDao: UserDao

    @MockitoBean
    private lateinit var lobbyService: LobbyService

    @MockitoBean
    private lateinit var gamePhaseService: GamePhaseService

    @MockitoBean
    private lateinit var gameStateGuard: GameStateGuard

    @MockitoBean
    private lateinit var gameSyncService: GameSyncService

    @MockitoBean
    private lateinit var purchaseService: PurchaseService

    @MockitoBean
    private lateinit var diceService: DiceService

    @MockitoBean
    private lateinit var gameEndBroadcaster: GameEndBroadcaster

    @MockitoBean
    private lateinit var accusationService: AccusationService

    @AfterEach
    fun disconnectSessions() {
        activeSessions.filter { it.isConnected }.forEach(StompSession::disconnect)
        activeSessions.clear()
    }

    @Test
    fun `authenticated player in game sends chat and both players receive it`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val player1 = LoginResult(sessionToken = "token1-$suffix", username = "player1_$suffix", userId = 101)
        val player2 = LoginResult(sessionToken = "token2-$suffix", username = "player2_$suffix", userId = 202)
        val gameId = 303
        configureDomainStubs(player1, player2, gameId)

        val session1 = connect(player1.sessionToken)
        val session2 = connect(player2.sessionToken)

        val gameQueue1 = LinkedBlockingQueue<JsonNode>()
        val gameQueue2 = LinkedBlockingQueue<JsonNode>()
        val gameTopic = "/topic/game/$gameId"
        session1.subscribe(gameTopic, queueHandler(gameQueue1))
        session2.subscribe(gameTopic, queueHandler(gameQueue2))
        waitForBrokerSubscriptions(gameTopic, gameQueue1, gameQueue2)

        // Player 1 sends chat message
        sendGameChat(session1, GameChatRequest(gameId = gameId, message = "Good start!"))

        val msg1 = takeMessage(gameQueue1, MessageType.CHAT)
        val msg2 = takeMessage(gameQueue2, MessageType.CHAT)

        assertEquals("CHAT", msg1["type"].asText())
        assertEquals("player1_$suffix", msg1["sender"].asText())
        assertEquals("Good start!", msg1["content"].asText())
        assertEquals(gameId, msg1["gameId"].asInt())

        assertEquals("CHAT", msg2["type"].asText())
        assertEquals("player1_$suffix", msg2["sender"].asText())
        assertEquals("Good start!", msg2["content"].asText())
        assertEquals(gameId, msg2["gameId"].asInt())
    }

    @Test
    fun `blank message is silently rejected and not broadcast`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val player1 = LoginResult(sessionToken = "token1-$suffix", username = "player1_$suffix", userId = 101)
        val player2 = LoginResult(sessionToken = "token2-$suffix", username = "player2_$suffix", userId = 202)
        val gameId = 303
        configureDomainStubs(player1, player2, gameId)

        val session1 = connect(player1.sessionToken)
        val session2 = connect(player2.sessionToken)

        val gameQueue1 = LinkedBlockingQueue<JsonNode>()
        val gameQueue2 = LinkedBlockingQueue<JsonNode>()
        val gameTopic = "/topic/game/$gameId"
        session1.subscribe(gameTopic, queueHandler(gameQueue1))
        session2.subscribe(gameTopic, queueHandler(gameQueue2))
        waitForBrokerSubscriptions(gameTopic, gameQueue1, gameQueue2)

        // Send blank message
        sendGameChat(session1, GameChatRequest(gameId = gameId, message = "   "))

        // No message should be received (timeout expected)
        val poll1 = gameQueue1.poll(1, TimeUnit.SECONDS)
        val poll2 = gameQueue2.poll(1, TimeUnit.SECONDS)
        assertEquals(null, poll1)
        assertEquals(null, poll2)
    }

    @Test
    fun `message exceeding max length is silently rejected and not broadcast`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val player1 = LoginResult(sessionToken = "token1-$suffix", username = "player1_$suffix", userId = 101)
        val player2 = LoginResult(sessionToken = "token2-$suffix", username = "player2_$suffix", userId = 202)
        val gameId = 303
        configureDomainStubs(player1, player2, gameId)

        val session1 = connect(player1.sessionToken)
        val session2 = connect(player2.sessionToken)

        val gameQueue1 = LinkedBlockingQueue<JsonNode>()
        val gameQueue2 = LinkedBlockingQueue<JsonNode>()
        val gameTopic = "/topic/game/$gameId"
        session1.subscribe(gameTopic, queueHandler(gameQueue1))
        session2.subscribe(gameTopic, queueHandler(gameQueue2))
        waitForBrokerSubscriptions(gameTopic, gameQueue1, gameQueue2)

        // Send oversized message (max is 300 chars)
        val tooLong = "x".repeat(301)
        sendGameChat(session1, GameChatRequest(gameId = gameId, message = tooLong))

        // No message should be received (timeout expected)
        val poll1 = gameQueue1.poll(1, TimeUnit.SECONDS)
        val poll2 = gameQueue2.poll(1, TimeUnit.SECONDS)
        assertEquals(null, poll1)
        assertEquals(null, poll2)
    }

    @Test
    fun `user not in game is silently rejected and message not broadcast`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val inGamePlayer = LoginResult(sessionToken = "token1-$suffix", username = "inGame_$suffix", userId = 101)
        val outsidePlayer = LoginResult(sessionToken = "token2-$suffix", username = "outside_$suffix", userId = 202)
        val gameId = 303

        // Configure stubs: only inGamePlayer is in the game
        val inGameUser = user(inGamePlayer)
        val outsideUser = user(outsidePlayer)
        whenever(userDao.findBySessionToken(inGamePlayer.sessionToken)).thenReturn(inGameUser)
        whenever(userDao.findBySessionToken(outsidePlayer.sessionToken)).thenReturn(outsideUser)
        whenever(gameSyncService.isUserInGame(inGamePlayer.userId, gameId)).thenReturn(true)
        whenever(gameSyncService.isUserInGame(outsidePlayer.userId, gameId)).thenReturn(false)

        val sessionIn = connect(inGamePlayer.sessionToken)
        val sessionOut = connect(outsidePlayer.sessionToken)

        val gameQueueIn = LinkedBlockingQueue<JsonNode>()
        val gameTopic = "/topic/game/$gameId"
        sessionIn.subscribe(gameTopic, queueHandler(gameQueueIn))
        waitForBrokerSubscriptions(gameTopic, gameQueueIn)

        // Outside player tries to send chat
        sendGameChat(sessionOut, GameChatRequest(gameId = gameId, message = "Trying to infiltrate!"))

        // No message should be received
        val poll = gameQueueIn.poll(1, TimeUnit.SECONDS)
        assertEquals(null, poll)
    }

    @Test
    fun `message with exactly max length is accepted and broadcast`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val player1 = LoginResult(sessionToken = "token1-$suffix", username = "player1_$suffix", userId = 101)
        val player2 = LoginResult(sessionToken = "token2-$suffix", username = "player2_$suffix", userId = 202)
        val gameId = 303
        configureDomainStubs(player1, player2, gameId)

        val session1 = connect(player1.sessionToken)
        val session2 = connect(player2.sessionToken)

        val gameQueue1 = LinkedBlockingQueue<JsonNode>()
        val gameQueue2 = LinkedBlockingQueue<JsonNode>()
        val gameTopic = "/topic/game/$gameId"
        session1.subscribe(gameTopic, queueHandler(gameQueue1))
        session2.subscribe(gameTopic, queueHandler(gameQueue2))
        waitForBrokerSubscriptions(gameTopic, gameQueue1, gameQueue2)

        // Send message with exactly 300 chars
        val maxMessage = "x".repeat(300)
        sendGameChat(session1, GameChatRequest(gameId = gameId, message = maxMessage))

        val msg1 = takeMessage(gameQueue1, MessageType.CHAT)
        val msg2 = takeMessage(gameQueue2, MessageType.CHAT)

        assertEquals(maxMessage, msg1["content"].asText())
        assertEquals(maxMessage, msg2["content"].asText())
    }

    @Test
    fun `message with leading and trailing whitespace is trimmed before broadcast`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val player1 = LoginResult(sessionToken = "token1-$suffix", username = "player1_$suffix", userId = 101)
        val player2 = LoginResult(sessionToken = "token2-$suffix", username = "player2_$suffix", userId = 202)
        val gameId = 303
        configureDomainStubs(player1, player2, gameId)

        val session1 = connect(player1.sessionToken)
        val session2 = connect(player2.sessionToken)

        val gameQueue1 = LinkedBlockingQueue<JsonNode>()
        val gameQueue2 = LinkedBlockingQueue<JsonNode>()
        val gameTopic = "/topic/game/$gameId"
        session1.subscribe(gameTopic, queueHandler(gameQueue1))
        session2.subscribe(gameTopic, queueHandler(gameQueue2))
        waitForBrokerSubscriptions(gameTopic, gameQueue1, gameQueue2)

        sendGameChat(session1, GameChatRequest(gameId = gameId, message = "  trimmed message  "))

        val msg1 = takeMessage(gameQueue1, MessageType.CHAT)
        val msg2 = takeMessage(gameQueue2, MessageType.CHAT)

        assertEquals("trimmed message", msg1["content"].asText())
        assertEquals("trimmed message", msg2["content"].asText())
    }

    // Helper methods

    private fun configureDomainStubs(player1: LoginResult, player2: LoginResult, gameId: Int) {
        val user1 = user(player1)
        val user2 = user(player2)
        whenever(userDao.findBySessionToken(player1.sessionToken)).thenReturn(user1)
        whenever(userDao.findBySessionToken(player2.sessionToken)).thenReturn(user2)
        whenever(gameSyncService.isUserInGame(player1.userId, gameId)).thenReturn(true)
        whenever(gameSyncService.isUserInGame(player2.userId, gameId)).thenReturn(true)
    }

    private fun connect(sessionToken: String): StompSession {
        val connectHeaders = StompHeaders().apply {
            add("Authorization", "Bearer $sessionToken")
        }
        return stompClient()
            .connectAsync(
                "ws://localhost:$port/ws",
                WebSocketHttpHeaders(),
                connectHeaders,
                object : StompSessionHandlerAdapter() {},
            )
            .get(10, TimeUnit.SECONDS)
            .also { session ->
                activeSessions.add(session)
            }
    }

    private fun stompClient(): WebSocketStompClient =
        WebSocketStompClient(StandardWebSocketClient()).apply {
            messageConverter = MappingJackson2MessageConverter().apply {
                objectMapper = mapper
            }
        }

    private fun queueHandler(queue: BlockingQueue<JsonNode>): StompFrameHandler =
        object : StompFrameHandler {
            override fun getPayloadType(headers: StompHeaders): Type = JsonNode::class.java

            override fun handleFrame(headers: StompHeaders, payload: Any?) {
                queue.offer(payload as JsonNode)
            }
        }

    private fun waitForBrokerSubscriptions(destination: String, vararg queues: BlockingQueue<JsonNode>) {
        val ready = BooleanArray(queues.size)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BROKER_SUBSCRIPTION_TIMEOUT_SECONDS)

        while (System.nanoTime() < deadline) {
            val probeId = UUID.randomUUID().toString()
            messagingTemplate.convertAndSend(
                destination,
                WebSocketMessage(
                    type = MessageType.CHAT,
                    sender = "test",
                    payload = mapOf("event" to "SUBSCRIPTION_READY", "probeId" to probeId),
                ),
            )

            queues.forEachIndexed { index, queue ->
                if (drainSubscriptionProbes(queue, probeId)) {
                    ready[index] = true
                }
            }

            if (ready.all { it }) {
                return
            }
        }

        error("Timed out waiting for broker subscriptions to $destination; ready=${ready.toList()}")
    }

    private fun drainSubscriptionProbes(queue: BlockingQueue<JsonNode>, probeId: String): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BROKER_SUBSCRIPTION_PROBE_WAIT_MILLIS)
        var receivedExpectedProbe = false
        while (System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val message = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: return receivedExpectedProbe
            if (
                message.path("type").asText() == MessageType.CHAT.name &&
                message.path("payload").path("probeId").asText() == probeId
            ) {
                receivedExpectedProbe = true
            }
        }
        return receivedExpectedProbe
    }

    private fun sendGameChat(session: StompSession, request: GameChatRequest) {
        val headers = StompHeaders().apply {
            this.destination = "/app/game.chat.send"
            contentType = MimeTypeUtils.APPLICATION_JSON
        }
        session.send(headers, request)
    }

    private fun takeMessage(queue: BlockingQueue<JsonNode>, type: MessageType): JsonNode {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(MESSAGE_TIMEOUT_SECONDS)
        val seen = mutableListOf<String>()
        while (System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val message = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: break
            seen += message.toString()
            if (message["type"].asText() == type.name) {
                return message
            }
        }
        error("Timed out waiting for $type; seen=$seen")
    }

    private data class LoginResult(
        val sessionToken: String,
        val username: String,
        val userId: Int,
    )

    private fun user(login: LoginResult): UserModel =
        UserModel(
            id = login.userId,
            username = login.username,
            passwordHash = null,
            sessionToken = login.sessionToken,
            totalWins = 0,
            totalGamesPlayed = 0,
            isAdmin = false,
        )
}
