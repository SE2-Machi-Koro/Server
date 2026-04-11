package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.machikoro.server.database.Users
import org.machikoro.server.domain.models.UserModel
import org.springframework.stereotype.Repository

@Repository
class UserDao {

    private fun ResultRow.toUserModel() = UserModel(
        id = this[Users.id].value,
        username = this[Users.username],
        totalWins = this[Users.totalWins],
        totalGamesPlayed = this[Users.totalGamesPlayed],
        sessionToken = this[Users.sessionToken],
    )

    fun findById(id: Int): UserModel? = transaction {
        Users.selectAll()
            .where { Users.id eq id }
            .singleOrNull()
            ?.toUserModel()
    }

    fun findByUsername(username: String): UserModel? = transaction {
        Users.selectAll()
            .where { Users.username eq username }
            .singleOrNull()
            ?.toUserModel()
    }

    fun findBySessionToken(token: String): UserModel? = transaction {
        Users.selectAll()
            .where { Users.sessionToken eq token }
            .singleOrNull()
            ?.toUserModel()
    }

    fun create(username: String): Int = transaction {
        Users.insert {
            it[Users.username] = username
        }[Users.id].value
    }

    fun updateSessionToken(id: Int, token: String?): Unit = transaction {
        Users.update({ Users.id eq id }) {
            it[Users.sessionToken] = token
        }
    }

    fun incrementWins(id: Int): Unit = transaction {
        Users.update({ Users.id eq id }) {
            it[Users.totalWins] = Users.totalWins + 1
        }
    }

    fun incrementGamesPlayed(id: Int): Unit = transaction {
        Users.update({ Users.id eq id }) {
            it[Users.totalGamesPlayed] = Users.totalGamesPlayed + 1
        }
    }
}