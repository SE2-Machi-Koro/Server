package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.UserDao
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.GameStateGuard
import org.machikoro.server.service.GameSyncService
import org.machikoro.server.service.LobbyService
import org.machikoro.server.service.PurchaseService
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * #305 AC: when `debug.enabled=false` the `@ConditionalOnProperty`-gated [DebugController]
 * is not registered, so `POST /debug/end-game` must return 404. Boots the web stack with
 * the persistence layer mocked, mirroring [GameWebSocketBroadcastIntegrationTest]'s setup.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "debug.enabled=false",
        "spring.docker.compose.enabled=false",
        "spring.flyway.enabled=false",
        "machikoro.db.init.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DebugEndGameDisabledIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val httpClient: HttpClient = HttpClient.newHttpClient()

    @MockitoBean private lateinit var userDao: UserDao
    @MockitoBean private lateinit var lobbyService: LobbyService
    @MockitoBean private lateinit var gamePhaseService: GamePhaseService
    @MockitoBean private lateinit var gameStateGuard: GameStateGuard
    @MockitoBean private lateinit var gameSyncService: GameSyncService
    @MockitoBean private lateinit var purchaseService: PurchaseService

    @Test
    fun `end-game endpoint is not accessible when debug is disabled`() {
        // With debug.enabled=false the gated DebugController bean is never created, AND
        // SecurityConfig only permits /debug/** when debug.enabled=true. Otherwise the POST
        // falls through to anyRequest().authenticated() + CSRF and is rejected with 403
        // before it could reach the dispatcher's would-be 404 — a stronger "not accessible"
        // guarantee than the literal 404 in #305's acceptance criteria.
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/debug/end-game"))
            .header("Authorization", "Bearer any-token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"gameId":1}"""))
            .build()

        val status = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()

        assertEquals(403, status)
    }
}
