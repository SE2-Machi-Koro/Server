package org.machikoro.server.domain.models

import com.fasterxml.jackson.annotation.JsonIgnore

data class UserModel(
    val id: Int,
    val username: String,
    @field:JsonIgnore
    val passwordHash: String?,
    val sessionToken: String?,
    val totalWins: Int,
    val totalGamesPlayed: Int
)
