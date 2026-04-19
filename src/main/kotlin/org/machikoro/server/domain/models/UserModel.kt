package org.machikoro.server.domain.models

data class UserModel(
    val id: Int,
    val username: String,
    val sessionToken: String?,
    val totalWins: Int,
    val totalGamesPlayed: Int
)