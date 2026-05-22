package org.machikoro.server.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.machikoro.server.dto.LoginRequest
import org.machikoro.server.dto.LogoutRequest
import org.machikoro.server.dto.RegisterRequest
import org.machikoro.server.exception.InvalidCredentialsException
import org.machikoro.server.service.AuthService
import org.machikoro.server.service.GameSyncService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
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
        return try {
            val response = authService.register(request.username, request.password)
            logger.info("Registered user '{}'", response.username)
            ResponseEntity.ok(response)
        } catch (e: Exception) {
            logger.warn("Registration failed for '{}': {}", request.username, e.message)
            ResponseEntity.badRequest().body(e.message)
        }
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate and receive a session token")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<Any> {
        return try {
            val response = authService.login(request.username, request.password)
            val sessionState = gameSyncService.resolveSessionState(response.userId)
            logger.info("Logged in user '{}'", response.username)
            ResponseEntity.ok(response.copy(sessionState = sessionState))
        } catch (e: InvalidCredentialsException) {
            logger.warn("Login failed for '{}': invalid credentials", request.username)
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.message)
        } catch (e: Exception) {
            logger.warn("Login failed for '{}': {}", request.username, e.message)
            ResponseEntity.badRequest().body(e.message)
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "Invalidate the given session token")
    fun logout(@Valid @RequestBody request: LogoutRequest): ResponseEntity<Any> {
        return try {
            authService.logout(request.sessionToken)
            ResponseEntity.ok().build()
        } catch (e: Exception) {
            logger.warn("Logout failed: {}", e.message)
            ResponseEntity.badRequest().body(e.message)
        }
    }
}
