package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request to register a new user — sent to POST /auth/register")
data class RegisterRequest(
    @Schema(description = "Desired username, 1-50 characters", example = "alice")
    val username: String,
    @Schema(description = "Raw password — server hashes via BCrypt before persisting", example = "hunter2")
    val password: String,
)
