package org.machikoro.server.database.entities

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.machikoro.server.database.Users
import org.machikoro.server.domain.models.UserModel

class UserEntity(id: EntityID<Int>) : IntEntity(id) {
    /**
     * Companion object required by Exposed DAO
     * - Acts as a factory for creating and querying entities
     */
    companion object : IntEntityClass<UserEntity>(Users)

    var username by Users.username
    var sessionToken by Users.sessionToken
    var totalWins by Users.totalWins
    var totalGamesPlayed by Users.totalGamesPlayed

    /**
     * Converts this database entity into a domain model
     * Ensures:
     * - Separation between persistence and business layer
     * - Domain model remain independent of Exposed/DB concerns
     */
    fun toModel() = UserModel(
        id = id.value,
        username = username,
        sessionToken = sessionToken,
        totalWins = totalWins,
        totalGamesPlayed = totalGamesPlayed
    )
}