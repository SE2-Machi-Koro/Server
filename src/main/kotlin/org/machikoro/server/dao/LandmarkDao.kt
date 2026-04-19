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

    fun findAll(): List<LandmarkModel> = transaction {
        LandmarkEntity.all().map { it.toModel() }
    }

    fun findByLandmarkType(landmarkType: LandmarkType): LandmarkModel? = transaction {
        LandmarkEntity.find { Landmarks.landmarkType eq landmarkType }
            .singleOrNull()
            ?.toModel()
    }
}