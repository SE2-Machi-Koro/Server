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

    private fun ResultRow.toPlayerLandmarkModel() = PlayerLandmarkModel(
        playerId = this[PlayerLandmarks.playerId].value,
        landmarkType = this[PlayerLandmarks.landmarkType],
        isBuilt = this[PlayerLandmarks.isBuilt]
    )

    fun findByPlayerId(playerId: Int): List<PlayerLandmarkModel> = transaction {
        PlayerLandmarks.selectAll()
            .where { PlayerLandmarks.playerId eq playerId }
            .map { it.toPlayerLandmarkModel() }
    }

    fun findByPlayerIdAndType(playerId: Int, landmarkType: LandmarkType): PlayerLandmarkModel? = transaction {
        PlayerLandmarks.selectAll()
            .where {
                (PlayerLandmarks.playerId eq playerId) and
                        (PlayerLandmarks.landmarkType eq landmarkType)
            }
            .singleOrNull()
            ?.toPlayerLandmarkModel()
    }

    fun initForPlayer(playerId: Int): Unit = transaction {
        LandmarkType.entries.forEach { type ->
            PlayerLandmarks.insertIgnore {
                it[PlayerLandmarks.playerId] = playerId
                it[PlayerLandmarks.landmarkType] = type
                it[PlayerLandmarks.isBuilt] = false
            }
        }
    }

    fun markBuilt(playerId: Int, landmarkType: LandmarkType): Unit = transaction {
        PlayerLandmarks.update({
            (PlayerLandmarks.playerId eq playerId) and
                    (PlayerLandmarks.landmarkType eq landmarkType)
        }) {
            it[PlayerLandmarks.isBuilt] = true
        }
    }

    fun allBuilt(playerId: Int): Boolean = transaction {
        val landmarks = PlayerLandmarks.selectAll()
            .where { PlayerLandmarks.playerId eq playerId }
            .toList()

        landmarks.isNotEmpty() && landmarks.all { it[PlayerLandmarks.isBuilt] }
    }

    fun findAll(): List<PlayerLandmarkModel> = transaction {
        PlayerLandmarks.selectAll().map {
            PlayerLandmarkModel(
                playerId = it[PlayerLandmarks.playerId].value,
                landmarkType = it[PlayerLandmarks.landmarkType],
                isBuilt = it[PlayerLandmarks.isBuilt]
            )
        }
    }

    fun delete(playerId: Int, landmarkType: LandmarkType): Unit = transaction {
        PlayerLandmarks.deleteWhere {
            (PlayerLandmarks.playerId eq playerId) and
                    (PlayerLandmarks.landmarkType eq landmarkType)
        }
    }
}