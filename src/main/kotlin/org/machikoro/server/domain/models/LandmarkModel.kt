package org.machikoro.server.domain.models

import org.machikoro.server.domain.enums.LandmarkType

data class LandmarkModel(
    val id: Int,
    val landmarkType: LandmarkType,
    val cost: Int
)