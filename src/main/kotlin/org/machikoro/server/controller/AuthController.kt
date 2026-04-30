package org.machikoro.server.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.machikoro.server.dto.RegisterRequest
import org.machikoro.server.service.AuthService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
@Tag(name = "Authentication", description = "User registration, login, and session management")
class AuthController(private val authService: AuthService) {

    @PostMapping("/register")
    @Operation(summary = "Register a new user with username and password")
    fun register(@RequestBody request: RegisterRequest): ResponseEntity<Any> {
        return try {
            ResponseEntity.ok(authService.register(request.username, request.password))
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(e.message)
        }
    }
}
