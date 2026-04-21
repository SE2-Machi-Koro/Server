package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.database.entities.LandmarkEntity
import org.machikoro.server.database.Landmarks
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.models.LandmarkModel
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
class LandmarkDao {

    /**
     * Finds all available card definitions from database
     */
    fun findAll(): List<LandmarkModel> = transaction {
        LandmarkEntity.all().map { it.toModel() }
    }

    /**
     * Find landmarks by its unique landmark type
     * Returns null if no matching landmark exists
     */
    fun findByLandmarkType(landmarkType: LandmarkType): LandmarkModel? = transaction {
        LandmarkEntity.find { Landmarks.landmarkType eq landmarkType }
            .singleOrNull()
            ?.toModel()
    }
}