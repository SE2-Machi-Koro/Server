package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request to authenticate an existing user — sent to POST /auth/login")
data class LoginRequest(
    @Schema(description = "Username — case-insensitive, leading/trailing whitespace ignored", example = "alice")
    val username: String,
    @Schema(description = "Raw password — server compares to the stored BCrypt hash", example = "hunter2")
    val password: String,
)
