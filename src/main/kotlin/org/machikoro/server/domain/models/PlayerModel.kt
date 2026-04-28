package org.machikoro.server.domain.models

data class PlayerModel(
    val id: Int,
    val gameId: Int,
    val userId: Int,
    val turnOrder: Int,
    val coins: Int
)