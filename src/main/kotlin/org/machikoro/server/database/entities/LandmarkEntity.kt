package org.machikoro.server.database.entities

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.machikoro.server.database.Landmarks
import org.machikoro.server.domain.models.LandmarkModel

class LandmarkEntity(id: EntityID<Int>) : IntEntity(id) {
    /**
     * Companion object required by Exposed DAO
     * - Acts as a factory for creating and querying entities
     */
    companion object : IntEntityClass<LandmarkEntity>(Landmarks)

    var landmarkType by Landmarks.landmarkType
    var name by Landmarks.name
    var cost by Landmarks.cost

    /**
     * Converts this database entity into a domain model
     * Ensures:
     * - Separation between persistence and business layer
     * - Domain model remain independent of Exposed/DB concerns
     */
    fun toModel() = LandmarkModel(
        id = id.value,
        landmarkType = landmarkType,
        name = name,
        cost = cost
    )
}