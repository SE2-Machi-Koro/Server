package org.machikoro.server.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.machikoro.server.dto.LoginRequest
import org.machikoro.server.dto.LogoutRequest
import org.machikoro.server.dto.RegisterRequest
import org.machikoro.server.service.AuthService
import org.machikoro.server.service.GameSyncService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
@Tag(name = "Authentication", description = "User registration, login, and session management")
class AuthController(
    private val authService: AuthService,
    private val gameSyncService: GameSyncService,
) {
    private val logger = LoggerFactory.getLogger(AuthController::class.java)

    @PostMapping("/register")
    @Operation(summary = "Register a new user with username and password")
    fun register(@Valid @RequestBody request: RegisterRequest): ResponseEntity<Any> {
        val response = authService.register(request.username, request.password)
        logger.info("Registered user '{}'", response.username)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate and receive a session token")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<Any> {
        val response = authService.login(request.username, request.password)
        val sessionState = gameSyncService.resolveSessionState(response.userId)
        logger.info("Logged in user '{}'", response.username)
        return ResponseEntity.ok(response.copy(sessionState = sessionState))
    }

    @PostMapping("/logout")
    @Operation(summary = "Invalidate the given session token")
    fun logout(@Valid @RequestBody request: LogoutRequest): ResponseEntity<Any> {
        authService.logout(request.sessionToken)
        return ResponseEntity.ok().build()
    }
}
