package org.machikoro.server.handler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.models.UserModel
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.service.AccusationService
import org.machikoro.server.service.DiceService
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.GameStateGuard
import org.machikoro.server.service.GameSyncService
import org.machikoro.server.service.LobbyService
import org.machikoro.server.service.PurchaseService
import org.machikoro.server.service.interfaces.EarningsService
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.converter.MappingJackson2MessageConverter
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
import java.lang.reflect.Type
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * End-to-end proof for #427: a [CustomWebSocketException] thrown by a
 * `@MessageMapping` handler must be caught by [GlobalWebSocketExceptionHandler]
 * (registered as `@ControllerAdvice`) and delivered to the originating client on
 * its user-scoped `/queue/errors` destination.
 *
 * Before the fix the handler was a plain `@Controller`, so its
 * `@MessageExceptionHandler` methods applied to nothing: the exception was
 * logged as unhandled and no error frame reached the client — this test would
 * time out waiting for the frame.
 */
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
class WebSocketExceptionAdviceIntegrationTest {
    private companion object {
        private const val ERROR_TIMEOUT_SECONDS = 20L
        private const val RESEND_POLL_MILLIS = 500L
        private const val USER_ERROR_QUEUE = "/user/queue/errors"
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var mapper: ObjectMapper

    private val activeSessions = mutableListOf<StompSession>()

    // Mocked so a CONNECT can authenticate without a database.
    @MockitoBean
    private lateinit var userDao: UserDao

    // Mocked so endTurn deterministically rejects the caller as a non-active player.
    @MockitoBean
    private lateinit var gameStateGuard: GameStateGuard

    // Remaining controller collaborators that would otherwise touch the (excluded)
    // DataSource at runtime; mocked so the context loads, mirroring the broadcast IT.
    @MockitoBean
    private lateinit var lobbyService: LobbyService

    @MockitoBean
    private lateinit var gamePhaseService: GamePhaseService

    @MockitoBean
    private lateinit var gameSyncService: GameSyncService

    @MockitoBean
    private lateinit var purchaseService: PurchaseService

    @MockitoBean
    private lateinit var diceService: DiceService

    @MockitoBean
    private lateinit var earningsService: EarningsService

    @MockitoBean
    private lateinit var accusationService: AccusationService

    @AfterEach
    fun disconnectSessions() {
        activeSessions.filter { it.isConnected }.forEach(StompSession::disconnect)
        activeSessions.clear()
    }

    @Test
    fun `not-your-turn is delivered to the caller's user error queue`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val token = "tok-$suffix"
        val username = "ws_user_$suffix"
        val userId = 909
        val gameId = 404

        whenever(userDao.findBySessionToken(token)).thenReturn(user(userId, username, token))
        whenever(gameStateGuard.ensureSenderIsActivePlayer(eq(gameId), any<UserPrincipal>()))
            .thenThrow(CustomWebSocketException(errorCode = "NOT_YOUR_TURN", message = "It is not your turn"))

        val session = connect(token)
        val errorQueue = LinkedBlockingQueue<JsonNode>()
        // The handler replies to the session-scoped destination that Spring
        // rewrites this `/user/queue/errors` subscription to.
        session.subscribe(USER_ERROR_QUEUE, queueHandler(errorQueue))

        val error = sendEndTurnUntilErrorReceived(session, gameId, errorQueue)
        assertEquals("NOT_YOUR_TURN", error["code"].asText())
        assertEquals("It is not your turn", error["message"].asText())
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
            .also(activeSessions::add)
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

    private fun sendJson(session: StompSession, destination: String, value: Any) {
        val headers = StompHeaders().apply {
            this.destination = destination
            contentType = MimeTypeUtils.APPLICATION_JSON
        }
        session.send(headers, value)
    }

    /**
     * Sends `/app/game.endTurn` (which the stubbed guard always rejects) and waits
     * for the resulting error frame on the caller's user queue, resending on a
     * short interval until one arrives.
     *
     * Resending defeats the STOMP subscription race: a subscription registers
     * asynchronously, and the broker silently drops a reply sent before it is
     * live. Each send re-exercises the real production path — handler throws →
     * `@ControllerAdvice` catches → session-scoped send replies to this session — so the
     * first frame received proves the advice is wired in.
     */
    private fun sendEndTurnUntilErrorReceived(
        session: StompSession,
        gameId: Int,
        queue: BlockingQueue<JsonNode>,
    ): JsonNode {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ERROR_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            sendJson(session, "/app/game.endTurn", mapOf("gameId" to gameId))

            val pollDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RESEND_POLL_MILLIS)
            while (System.nanoTime() < pollDeadline) {
                val remaining = pollDeadline - System.nanoTime()
                val message = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                if (message.hasNonNull("code")) {
                    return message
                }
            }
        }
        error("Timed out waiting for an error frame on $USER_ERROR_QUEUE")
    }

    private fun user(userId: Int, username: String, token: String): UserModel =
        UserModel(
            id = userId,
            username = username,
            passwordHash = null,
            sessionToken = token,
            totalWins = 0,
            totalGamesPlayed = 0,
            isAdmin = false,
        )
}
