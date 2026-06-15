package org.machikoro.server.controller

import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.UserDao
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.GameStartedException
import org.machikoro.server.exception.InvalidCredentialsException
import org.machikoro.server.exception.InvalidSessionTokenException
import org.machikoro.server.exception.NotAdminException
import org.machikoro.server.service.AuthService
import org.machikoro.server.service.DebugService
import org.machikoro.server.service.GameEndBroadcaster
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.GameSyncService
import org.machikoro.server.service.LobbyService
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.ResultMatcher
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(controllers = [AuthController::class, LobbyController::class, DebugController::class])
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = ["debug.enabled=true"])
class RestErrorHandlingRegressionTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var authService: AuthService

    @MockitoBean
    private lateinit var gameSyncService: GameSyncService

    @MockitoBean
    private lateinit var lobbyService: LobbyService

    @MockitoBean
    private lateinit var debugService: DebugService

    @MockitoBean
    private lateinit var gameEndBroadcaster: GameEndBroadcaster

    @MockitoBean
    private lateinit var gamePhaseService: GamePhaseService

    @MockitoBean
    private lateinit var userDao: UserDao

    @Test
    fun `login failure returns structured credentials error without account details`() {
        whenever(authService.login("alice", "wrong-password"))
            .thenThrow(InvalidCredentialsException("Invalid username or password"))

        postJson("/auth/login", """{"username":"alice","password":"wrong-password"}""")
            .expectApiError(status().isUnauthorized, "INVALID_CREDENTIALS", "Invalid username or password")
            .andExpect(content().string(not(containsString("wrong-password"))))
    }

    @Test
    fun `missing debug session returns structured unauthenticated error`() {
        whenever(debugService.validateAdmin(isNull()))
            .thenThrow(InvalidSessionTokenException("Missing Authorization header"))

        mockMvc.perform(post("/debug/seed"))
            .expectApiError(status().isUnauthorized, "UNAUTHENTICATED", "Missing Authorization header")
    }

    @Test
    fun `non-admin debug session returns structured forbidden error`() {
        whenever(debugService.validateAdmin(eq("Bearer user-token")))
            .thenThrow(NotAdminException("Admin access required"))

        mockMvc.perform(post("/debug/seed").header("Authorization", "Bearer user-token"))
            .expectApiError(status().isForbidden, "NOT_ADMIN", "Admin access required")
    }

    @Test
    @WithUserPrincipal(userId = 1)
    fun `lobby start returns structured not-found error`() {
        whenever(lobbyService.startGame(gameId = 404, requestingUserId = 1))
            .thenThrow(GameNotFoundException("Game 404 not found"))

        mockMvc.perform(post("/lobby/{gameId}/start", 404))
            .expectApiError(status().isNotFound, "GAME_NOT_FOUND", "Game 404 not found")
    }

    @Test
    @WithUserPrincipal(userId = 1)
    fun `lobby start returns structured invalid game-state error`() {
        whenever(lobbyService.startGame(gameId = 7, requestingUserId = 1))
            .thenThrow(GameStartedException("Game 7 has already started"))

        mockMvc.perform(post("/lobby/{gameId}/start", 7))
            .expectApiError(status().isBadRequest, "GAME_STARTED", "Game 7 has already started")
    }

    @Test
    @WithUserPrincipal(userId = 1)
    fun `unexpected REST exception returns generic error without internal details`() {
        whenever(lobbyService.startGame(gameId = 500, requestingUserId = 1))
            .thenThrow(IllegalStateException("jdbc:postgresql://prod.internal password=secret"))

        mockMvc.perform(post("/lobby/{gameId}/start", 500))
            .expectApiError(
                status().isInternalServerError,
                "INTERNAL_ERROR",
                "Unexpected error while processing request",
            )
            .andExpect(content().string(not(containsString("prod.internal"))))
            .andExpect(content().string(not(containsString("password=secret"))))
    }

    private fun postJson(path: String, body: String): ResultActions =
        mockMvc.perform(
            post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    private fun ResultActions.expectApiError(
        statusMatcher: ResultMatcher,
        code: String,
        message: String,
    ): ResultActions =
        andExpect(statusMatcher)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.message").value(message))
            .andExpect(jsonPath("$.timestamp").isNumber)
}
