package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Response from a successful login — caller stores the session token and uses it for subsequent authenticated calls")
data class LoginResponse(
    @Schema(description = "Opaque session token. Treat as a secret. Echo back to /auth/logout to invalidate.", example = "550e8400-e29b-41d4-a716-446655440000")
    val sessionToken: String,
    @Schema(description = "Canonical (trimmed and lower-cased) username of the authenticated user", example = "alice")
    val username: String,
    @Schema(description = "Unique ID of the authenticated user", example = "42")
    val userId: Int, // NEU
)