package org.machikoro.server.database.entities

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.machikoro.server.database.Landmarks
import org.machikoro.server.domain.models.LandmarkModel

class LandmarkEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<LandmarkEntity>(Landmarks)

    var landmarkType by Landmarks.landmarkType
    var name by Landmarks.name
    var cost by Landmarks.cost

    fun toModel() = LandmarkModel(
        id = id.value,
        landmarkType = landmarkType,
        name = name,
        cost = cost
    )
}