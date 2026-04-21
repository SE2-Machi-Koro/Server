package org.machikoro.server.domain.models

import org.machikoro.server.domain.enums.LandmarkType

/**
 * Domain model representing data used in the application's core logic
 * - Encapsulates pure business data independent of persistence and frameworks
 * - Used by the service layer to implement game logic
 * - Can be used for DTO's
 * - Does not contain database logic
 * - Used to represent the game state
 */
data class LandmarkModel(
    val id: Int,
    val landmarkType: LandmarkType,
    val name: String,
    val cost: Int
)