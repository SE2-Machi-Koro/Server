package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "Request to register a new user — sent to POST /auth/register")
data class RegisterRequest(
    @Schema(description = "Desired username, 1-50 characters", example = "alice")
    @field:NotBlank(message = "Username must not be blank")
    @field:Size(max = 50, message = "Username must be at most 50 characters")
    val username: String,
    @Schema(description = "Raw password — server hashes via BCrypt before persisting", example = "hunter2")
    @field:NotBlank(message = "Password must not be blank")
    // BCrypt silently truncates input past 72 bytes; reject longer up front.
    @field:Size(max = 72, message = "Password must be at most 72 characters")
    val password: String,
)
