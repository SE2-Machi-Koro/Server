package org.machikoro.server.domain.models

import com.fasterxml.jackson.annotation.JsonProperty
import org.machikoro.server.domain.enums.LandmarkType

data class PlayerLandmarkModel(
    val playerId: Int,
    val landmarkType: LandmarkType,
    // Jackson strips "is" prefix from boolean getters without KotlinModule → force the correct key name.
    @get:JsonProperty("isBuilt")
    val isBuilt: Boolean
)