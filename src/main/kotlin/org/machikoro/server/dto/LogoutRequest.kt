package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request to invalidate a session token — sent to POST /auth/logout")
data class LogoutRequest(
    @Schema(description = "Session token previously issued by /auth/login", example = "550e8400-e29b-41d4-a716-446655440000")
    val sessionToken: String,
)
