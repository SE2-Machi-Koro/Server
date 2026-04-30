package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.machikoro.server.database.PlayerLandmarks
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.models.PlayerLandmarkModel
import org.springframework.stereotype.Repository

@Repository
class PlayerLandmarkDao {

    /**
     * Maps a raw database row (ResultRow) to a domain models
     * Needed because this DAO uses Exposed DSL instead of DAO entities
     */
    private fun ResultRow.toPlayerLandmarkModel() = PlayerLandmarkModel(
        playerId = this[PlayerLandmarks.playerId].value,
        landmarkType = this[PlayerLandmarks.landmarkType],
        isBuilt = this[PlayerLandmarks.isBuilt]
    )

    /**
     * Finds all landmarks for a given player
     */
    fun findByPlayerId(playerId: Int): List<PlayerLandmarkModel> = transaction {
        PlayerLandmarks.selectAll()
            .where { PlayerLandmarks.playerId eq playerId }
            .map { it.toPlayerLandmarkModel() }
    }

    /**
     * Finds a specific landmark for a player
     * Return null if player does not have this landmark entry
     */
    fun findByPlayerIdAndType(playerId: Int, landmarkType: LandmarkType): PlayerLandmarkModel? = transaction {
        PlayerLandmarks.selectAll()
            .where {
                (PlayerLandmarks.playerId eq playerId) and
                        (PlayerLandmarks.landmarkType eq landmarkType)
            }
            .singleOrNull()
            ?.toPlayerLandmarkModel()
    }

    /**
     * Initializes all landmark entries for a player
     * For each LandmarkType
     * - Creates an entry with isBuilt = false
     * - Prevents duplicate entries if initialization runs multiple times
     */
    fun initForPlayer(playerId: Int): Unit = transaction {
        LandmarkType.entries.forEach { type ->
            PlayerLandmarks.insertIgnore {
                it[PlayerLandmarks.playerId] = playerId
                it[PlayerLandmarks.landmarkType] = type
                it[PlayerLandmarks.isBuilt] = false
            }
        }
    }

    /**
     * Marks a specific landmark as built for a player
     */
    fun markBuilt(playerId: Int, landmarkType: LandmarkType): Unit = transaction {
        PlayerLandmarks.update({
            (PlayerLandmarks.playerId eq playerId) and
                    (PlayerLandmarks.landmarkType eq landmarkType)
        }) {
            it[PlayerLandmarks.isBuilt] = true
        }
    }

    /**
     * Checks whether a player has built all landmarks
     * Returns true only if:
     * - Player has landmark entries
     * - All landmarks are marked as built
     *
     * Can be used as a win condition
     */
    fun allBuilt(playerId: Int): Boolean = transaction {
        val landmarks = PlayerLandmarks.selectAll()
            .where { PlayerLandmarks.playerId eq playerId }
            .toList()

        landmarks.isNotEmpty() && landmarks.all { it[PlayerLandmarks.isBuilt] }
    }

    /**
     * Finds all player landmark entries across all players
     */
    fun findAll(): List<PlayerLandmarkModel> = transaction {
        PlayerLandmarks.selectAll()
            .map { it.toPlayerLandmarkModel() }
    }

    /**
     * Deletes a specific landmark entry for a player
     */
    fun delete(playerId: Int, landmarkType: LandmarkType): Unit = transaction {
        PlayerLandmarks.deleteWhere {
            (PlayerLandmarks.playerId eq playerId) and
                    (PlayerLandmarks.landmarkType eq landmarkType)
        }
    }

    /**
     * Deletes all landmark entries for a player
     */
    fun deleteAllByPlayerId(playerId: Int) = transaction {
        PlayerLandmarks.deleteWhere {
            PlayerLandmarks.playerId eq playerId
        }
    }
}