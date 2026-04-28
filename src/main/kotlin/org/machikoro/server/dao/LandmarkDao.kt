package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.database.entities.LandmarkEntity
import org.machikoro.server.database.Landmarks
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.models.LandmarkModel
import org.springframework.stereotype.Repository

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