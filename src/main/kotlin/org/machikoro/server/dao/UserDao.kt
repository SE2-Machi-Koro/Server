package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.machikoro.server.database.entities.UserEntity
import org.machikoro.server.database.Users
import org.machikoro.server.domain.models.UserModel
import org.springframework.stereotype.Repository

/**
 * Data Access Object responsible for interacting with the database
 * - Encapsulated all database operations
 * - Uses Exposed entities and transactions to access persistence layer
 * - Converts database entities into domain models via toModel()
 *
 * - Only layer that should directly access Exposed/DB tables
 * - Returns domain models instead of entities to keep persistence isolated
 * - All operations are executed inside a transaction
 *
 * DAOs are used by the service layer to retrieve and modify the game state
 */
@Repository
class UserDao {

    /**
     * Finds a specific user by their ID
     */
    fun findById(id: Int): UserModel? = transaction {
        UserEntity.findById(id)?.toModel()
    }

    /**
     * Finds a user by their username
     * Return null if not found
     */
    fun findByUsername(username: String): UserModel? = transaction {
        UserEntity.find { Users.username eq username }
            .singleOrNull()
            ?.toModel()
    }

    /**
     * Looks up a user based on their current active session token
     * Otherwise return null
     */
    fun findBySessionToken(token: String): UserModel? = transaction {
        UserEntity.find { Users.sessionToken eq token }
            .singleOrNull()
            ?.toModel()
    }

    /**
     * Creates a new user profile
     * Initializes gameplay statistics
     */
    fun create(username: String): Int = transaction {
        UserEntity.new {
            this.username = username
            this.sessionToken = null
            this.totalWins = 0
            this.totalGamesPlayed = 0
        }.id.value
    }

    /**
     * Updates authentication token for a user
     * Pass a newly generated token when the user logs in or
     * pass 'null' to invalidate the session when the user logs out
     */
    fun updateSessionToken(id: Int, token: String?): Unit = transaction {
        UserEntity.findById(id)?.sessionToken = token
    }

    /**
     * Increments user's total win count by 1
     */
    fun incrementWins(id: Int): Unit = transaction {
        Users.update({ Users.id eq id }) {
            it[totalWins] = totalWins + 1
        }
    }

    /**
     * Increments user's total games played count by 1
     */
    fun incrementGamesPlayed(id: Int): Unit = transaction {
        Users.update({ Users.id eq id }) {
            it[totalGamesPlayed] = totalGamesPlayed + 1
        }
    }

    /**
     * Finds all registered users in the system
     */
    fun findAll(): List<UserModel> = transaction {
        UserEntity.all().map { it.toModel() }
    }

    /**
     * Deletes a user by their ID
     */
    fun delete(id: Int): Unit = transaction {
        UserEntity.findById(id)?.delete()
    }
}