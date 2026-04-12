package org.machikoro.server.database.entities

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.machikoro.server.database.Users
import org.machikoro.server.domain.models.UserModel

class UserEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<UserEntity>(Users)

    var username by Users.username
    var sessionToken by Users.sessionToken
    var totalWins by Users.totalWins
    var totalGamesPlayed by Users.totalGamesPlayed

    fun toModel() = UserModel(
        id = id.value,
        username = username,
        sessionToken = sessionToken,
        totalWins = totalWins,
        totalGamesPlayed = totalGamesPlayed
    )
}