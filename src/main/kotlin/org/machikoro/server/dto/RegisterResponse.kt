package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Response from a successful registration — does not include password or session token")
data class RegisterResponse(
    @Schema(description = "Newly created user id", example = "42")
    val id: Int,
    @Schema(description = "Registered username", example = "alice")
    val username: String,
)
