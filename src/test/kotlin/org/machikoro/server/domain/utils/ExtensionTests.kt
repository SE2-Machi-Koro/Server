package org.machikoro.server.domain.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.LandmarkType

class ExtensionTests {

    @Test
    fun `CardType displayName formats correctly`() {
        assertEquals("Wheat Field", CardType.WHEAT_FIELD.displayName())
        assertEquals("Bakery", CardType.BAKERY.displayName())
    }

    @Test
    fun `LandmarkType displayName formats correctly`() {
        assertEquals("Train Station", LandmarkType.TRAIN_STATION.displayName())
        assertEquals("Radio Tower", LandmarkType.RADIO_TOWER.displayName())
    }
}