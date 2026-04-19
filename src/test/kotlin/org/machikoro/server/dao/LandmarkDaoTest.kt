package org.machikoro.server.dao

import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.Landmarks
import org.machikoro.server.domain.enums.LandmarkType

class LandmarkDaoTest : AbstractDBSetup() {

    private val dao = LandmarkDao()

    @BeforeEach
    fun seed() {
        transaction {
            Landmarks.insert {
                it[landmarkType] = LandmarkType.TRAIN_STATION
                it[name] = "Train Station"
                it[cost] = 4
            }
            Landmarks.insert {
                it[landmarkType] = LandmarkType.SHOPPING_MALL
                it[name] = "Shopping Mall"
                it[cost] = 10
            }
        }
    }

    @AfterEach
    fun cleanup() {
        transaction { Landmarks.deleteAll() }
    }

    @Test
    fun `findAll returns all seeded landmarks`() {
        assertEquals(2, dao.findAll().size)
    }

    @Test
    fun `findByLandmarkType returns correct landmark`() {
        val landmark = dao.findByLandmarkType(LandmarkType.TRAIN_STATION)
        assertNotNull(landmark)
        assertEquals("Train Station", landmark!!.name)
        assertEquals(4, landmark.cost)
    }

    @Test
    fun `findByLandmarkType returns null for unseeded landmark`() {
        assertNull(dao.findByLandmarkType(LandmarkType.RADIO_TOWER))
    }
}