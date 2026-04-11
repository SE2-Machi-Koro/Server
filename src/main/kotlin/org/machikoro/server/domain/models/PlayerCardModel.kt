package org.machikoro.server.domain.models

import org.machikoro.server.domain.enums.LandmarkType

data class PlayerCardModel(
    val playerId: Int,
    val landmarkType: LandmarkType,
    val isBuilt: Boolean
)