package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.machikoro.server.database.Landmarks
import org.machikoro.server.database.PlayerLandmarks
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.models.PlayerLandmarkModel
import org.machikoro.server.exception.LandmarkNotFoundException
import org.springframework.stereotype.Repository

@Repository
class PlayerLandmarkDao {

    /**
     * Maps a raw database row (ResultRow) to a domain model
     * Requires a join with Landmarks to resolve landmarkType from landmarkId
     */
    private fun ResultRow.toPlayerLandmarkModel() = PlayerLandmarkModel(
        playerId = this[PlayerLandmarks.playerId].value,
        landmarkType = this[Landmarks.landmarkType],
        isBuilt = this[PlayerLandmarks.isBuilt]
    )

    /**
     * Finds all landmarks for a given player, ordered alphabetically by
     * landmark type. Stable ordering matters because the result is surfaced
     * in `GameStateDto.playerLandmarks` and serialized to clients — without
     * an explicit order, the JSON output (and any downstream test assertions)
     * would be at the mercy of the DB's row-return order.
     */
    fun findByPlayerId(playerId: Int): List<PlayerLandmarkModel> = transaction {
        (PlayerLandmarks innerJoin Landmarks)
            .selectAll()
            .where { PlayerLandmarks.playerId eq playerId }
            .orderBy(Landmarks.landmarkType to SortOrder.ASC)
            .map { it.toPlayerLandmarkModel() }
    }

    /**
     * Finds a specific landmark for a player
     * Returns null if player does not have this landmark entry
     */
    fun findByPlayerIdAndType(playerId: Int, landmarkType: LandmarkType): PlayerLandmarkModel? = transaction {
        (PlayerLandmarks innerJoin Landmarks)
            .selectAll()
            .where {
                (PlayerLandmarks.playerId eq playerId) and
                        (Landmarks.landmarkType eq landmarkType)
            }
            .singleOrNull()
            ?.toPlayerLandmarkModel()
    }

    /**
     * Initializes all landmark entries for a player
     * For each LandmarkType, creates an entry with isBuilt = false
     * Prevents duplicate entries if initialization runs multiple times
     */
    fun initForPlayer(playerId: Int): Unit = transaction {
        val landmarkIdByType = Landmarks.selectAll()
            .associate { it[Landmarks.landmarkType] to it[Landmarks.id] }

        LandmarkType.entries.forEach { type ->
            // All landmark types must be seeded in the DB before a game can start.
            val landmarkId = landmarkIdByType[type]
                ?: throw LandmarkNotFoundException("Landmark type $type not seeded in database")
            PlayerLandmarks.insertIgnore {
                it[PlayerLandmarks.playerId] = playerId
                it[PlayerLandmarks.landmarkId] = landmarkId
                it[PlayerLandmarks.isBuilt] = false
            }
        }
    }

    /**
     * Marks a specific landmark as built for a player
     */
    fun markBuilt(playerId: Int, landmarkType: LandmarkType): Unit = transaction {
        val landmarkId = Landmarks.selectAll()
            .where { Landmarks.landmarkType eq landmarkType }
            .singleOrNull()?.get(Landmarks.id)
            ?: throw LandmarkNotFoundException("Landmark type $landmarkType not found in database")

        PlayerLandmarks.update({
            (PlayerLandmarks.playerId eq playerId) and
                    (PlayerLandmarks.landmarkId eq landmarkId)
        }) {
            it[PlayerLandmarks.isBuilt] = true
        }
    }

    /**
     * Checks whether a player has built all landmarks
     * Returns true only if all landmarks are marked as built
     * Used as win condition
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
        (PlayerLandmarks innerJoin Landmarks)
            .selectAll()
            .map { it.toPlayerLandmarkModel() }
    }

    /**
     * Deletes a specific landmark entry for a player
     *
     * @throws LandmarkNotFoundException if the landmark type doesn't exist in the database
     */
    fun delete(playerId: Int, landmarkType: LandmarkType): Unit = transaction {
        val landmarkId = Landmarks.selectAll()
            .where { Landmarks.landmarkType eq landmarkType }
            .singleOrNull()?.get(Landmarks.id)
            ?: throw LandmarkNotFoundException("Landmark type $landmarkType not found in database")

        PlayerLandmarks.deleteWhere {
            (PlayerLandmarks.playerId eq playerId) and
                    (PlayerLandmarks.landmarkId eq landmarkId)
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
