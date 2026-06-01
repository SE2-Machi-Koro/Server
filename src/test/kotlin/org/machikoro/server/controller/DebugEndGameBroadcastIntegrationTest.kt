package org.machikoro.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.domain.models.UserModel
import org.machikoro.server.dto.EndTurnOutcome
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.GameStateGuard
import org.machikoro.server.service.GameSyncService
import org.machikoro.server.service.LobbyService
import org.machikoro.server.service.PurchaseService
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.converter.MappingJackson2MessageConverter
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.lang.reflect.Type
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * #305 DoD: end-to-end WebSocket coverage of the debug end-game endpoint. Mirrors
 * [GameWebSocketBroadcastIntegrationTest] — boots the full web + STOMP stack with the
 * persistence layer mocked — and asserts that calling `POST /debug/end-game` emits a
 * `GAME_END` broadcast on `/topic/game/{id}` carrying the caller as winner. Runs with
 * `debug.enabled=true` so the gated DebugController is registered.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "debug.enabled=true",
        "spring.docker.compose.enabled=false",
        "spring.flyway.enabled=false",
        "machikoro.db.init.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DebugEndGameBroadcastIntegrationTest {
    private companion object {
        private const val MESSAGE_TIMEOUT_SECONDS = 20L
        private const val BROKER_SUBSCRIPTION_TIMEOUT_SECONDS = 10L
        private const val BROKER_SUBSCRIPTION_PROBE_WAIT_MILLIS = 250L
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var messagingTemplate: SimpMessagingTemplate

    private val httpClient: HttpClient = HttpClient.newHttpClient()
    private val activeSessions = mutableListOf<StompSession>()

    @MockitoBean
    private lateinit var userDao: UserDao

    @MockitoBean
    private lateinit var playerDao: PlayerDao

    @MockitoBean
    private lateinit var gameStateGuard: GameStateGuard

    @MockitoBean
    private lateinit var gamePhaseService: GamePhaseService

    @MockitoBean
    private lateinit var gameSyncService: GameSyncService

    // Mocked so the application context boots without a DataSource (mirrors the sibling test).
    @MockitoBean
    private lateinit var lobbyService: LobbyService

    @MockitoBean
    private lateinit var purchaseService: PurchaseService

    @AfterEach
    fun disconnectSessions() {
        activeSessions.filter { it.isConnected }.forEach(StompSession::disconnect)
        activeSessions.clear()
    }

    @Test
    fun `end-game endpoint broadcasts GAME_END with the caller as winner`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val adminToken = "admin-token-$suffix"
        val adminUserId = 101
        val gameId = 303
        val roundsPlayed = 4
        val admin = UserModel(
            id = adminUserId,
            username = "ws_admin_$suffix",
            passwordHash = null,
            sessionToken = adminToken,
            totalWins = 0,
            totalGamesPlayed = 0,
            isAdmin = true,
        )
        val callerPlayer = PlayerModel(id = 55, gameId = gameId, userId = adminUserId, turnOrder = 0, coins = 3, lastSeenAt = null)
        val game = GameModel(
            id = gameId,
            status = GameStatus.IN_PROGRESS,
            hostUserId = adminUserId,
            lobbyCode = "ABC$suffix".take(8).uppercase(),
            maxPlayers = 4,
            currentTurnIndex = 0,
            turnPhase = TurnPhase.ROLL_DICE,
            lastDiceRoll = null,
            roundNumber = roundsPlayed,
            hasPurchasedThisTurn = false,
        )

        whenever(userDao.findBySessionToken(adminToken)).thenReturn(admin)
        whenever(gameStateGuard.ensureGameIsRunning(gameId)).thenReturn(game)
        whenever(playerDao.findByGameIdAndUserId(gameId, adminUserId)).thenReturn(callerPlayer)
        whenever(gamePhaseService.finishGameWithWinner(eq(gameId), eq(callerPlayer), eq(roundsPlayed)))
            .thenReturn(EndTurnOutcome.Won(winnerId = callerPlayer.id, roundsPlayed = roundsPlayed))
        whenever(gameSyncService.buildSnapshot(gameId)).thenReturn(snapshot(game, callerPlayer))

        val session = connect(adminToken)
        val gameQueue = LinkedBlockingQueue<JsonNode>()
        val gameTopic = "/topic/game/$gameId"
        session.subscribe(gameTopic, queueHandler(gameQueue))
        waitForBrokerSubscriptions(gameTopic, gameQueue)

        val status = postEndGame(gameId, adminToken)
        assertEquals(200, status)

        val message = takeMessage(gameQueue, MessageType.GAME_END)
        assertEquals(gameId, message["gameId"].asInt())
        assertEquals(callerPlayer.id, message["payload"]["winnerId"].asInt())
        assertEquals(roundsPlayed, message["payload"]["roundsPlayed"].asInt())
    }

    private fun postEndGame(gameId: Int, token: String): Int {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/debug/end-game"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"gameId":$gameId}"""))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    private fun snapshot(game: GameModel, player: PlayerModel): GameStateDto =
        GameStateDto(
            game = game,
            players = listOf(player),
            playerCards = emptyMap(),
            playerLandmarks = emptyMap(),
            marketplace = emptyMap(),
            turnOrder = listOf(player.userId),
            activePlayerId = player.userId,
        )

    private fun connect(sessionToken: String): StompSession {
        val connectHeaders = StompHeaders().apply { add("Authorization", "Bearer $sessionToken") }
        return stompClient()
            .connectAsync(
                "ws://localhost:$port/ws",
                WebSocketHttpHeaders(),
                connectHeaders,
                object : StompSessionHandlerAdapter() {},
            )
            .get(10, TimeUnit.SECONDS)
            .also { activeSessions.add(it) }
    }

    private fun stompClient(): WebSocketStompClient =
        WebSocketStompClient(StandardWebSocketClient()).apply {
            messageConverter = MappingJackson2MessageConverter().apply { objectMapper = mapper }
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
                if (drainSubscriptionProbes(queue, probeId)) ready[index] = true
            }
            if (ready.all { it }) return
        }
        error("Timed out waiting for broker subscriptions to $destination; ready=${ready.toList()}")
    }

    private fun drainSubscriptionProbes(queue: BlockingQueue<JsonNode>, probeId: String): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BROKER_SUBSCRIPTION_PROBE_WAIT_MILLIS)
        var received = false
        while (System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val message = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: return received
            if (
                message.path("type").asText() == MessageType.CHAT.name &&
                message.path("payload").path("probeId").asText() == probeId
            ) {
                received = true
            }
        }
        return received
    }

    private fun takeMessage(queue: BlockingQueue<JsonNode>, type: MessageType): JsonNode {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(MESSAGE_TIMEOUT_SECONDS)
        val seen = mutableListOf<String>()
        while (System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val message = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: break
            seen += message.toString()
            if (message["type"].asText() == type.name) return message
        }
        error("Timed out waiting for $type; seen=$seen")
    }
}
