package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.database.entities.UserEntity
import org.machikoro.server.database.Users
import org.machikoro.server.domain.models.UserModel
import org.springframework.stereotype.Repository

@Repository
class UserDao {

    fun findById(id: Int): UserModel? = transaction {
        UserEntity.findById(id)?.toModel()
    }

    fun findByUsername(username: String): UserModel? = transaction {
        UserEntity.find { Users.username eq username }
            .singleOrNull()
            ?.toModel()
    }

    fun findBySessionToken(token: String): UserModel? = transaction {
        UserEntity.find { Users.sessionToken eq token }
            .singleOrNull()
            ?.toModel()
    }

    fun create(username: String): Int = transaction {
        UserEntity.new {
            this.username = username
            this.sessionToken = null
            this.totalWins = 0
            this.totalGamesPlayed = 0
        }.id.value
    }

    fun updateSessionToken(id: Int, token: String?): Unit = transaction {
        UserEntity.findById(id)?.sessionToken = token
    }

    fun incrementWins(id: Int): Unit = transaction {
        UserEntity.findById(id)?.apply {
            totalWins += 1
        }
    }

    fun incrementGamesPlayed(id: Int): Unit = transaction {
        UserEntity.findById(id)?.apply {
            totalGamesPlayed += 1
        }
    }
}