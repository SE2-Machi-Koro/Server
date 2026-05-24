package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "Request to authenticate an existing user — sent to POST /auth/login")
data class LoginRequest(
    @Schema(description = "Username — case-insensitive, leading/trailing whitespace ignored", example = "alice")
    @field:NotBlank(message = "Username must not be blank")
    val username: String,
    @Schema(description = "Raw password — server compares to the stored BCrypt hash", example = "hunter2")
    @field:NotBlank(message = "Password must not be blank")
    val password: String,
)
