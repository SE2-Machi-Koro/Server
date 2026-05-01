package org.machikoro.server.dao

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.Games
import org.machikoro.server.database.Landmarks
import org.machikoro.server.database.PlayerLandmarks
import org.machikoro.server.database.Players
import org.machikoro.server.database.Users
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.exception.LandmarkNotFoundException

class PlayerLandmarkDaoTest : AbstractDBSetup() {

    private val userDao = UserDao()
    private val gameDao = GameDao()
    private val playerDao = PlayerDao()
    private val playerLandmarkDao = PlayerLandmarkDao()

    private var playerId = 0

    @BeforeEach
    fun seed() {
        transaction {
            Landmarks.insertIgnore { it[Landmarks.landmarkType] = LandmarkType.TRAIN_STATION; it[Landmarks.cost] = 4 }
            Landmarks.insertIgnore { it[Landmarks.landmarkType] = LandmarkType.SHOPPING_MALL; it[Landmarks.cost] = 10 }
            Landmarks.insertIgnore { it[Landmarks.landmarkType] = LandmarkType.AMUSEMENT_PARK; it[Landmarks.cost] = 16 }
            Landmarks.insertIgnore { it[Landmarks.landmarkType] = LandmarkType.RADIO_TOWER; it[Landmarks.cost] = 22 }
        }
        val userId = userDao.create("landmark_user")
        val gameId = gameDao.create(userId)
        playerId = playerDao.addPlayer(gameId, userId).id
    }

    @AfterEach
    fun cleanup() {
        transaction {
            PlayerLandmarks.deleteAll()
            Players.deleteAll()
            Games.deleteAll()
            Users.deleteAll()
            Landmarks.deleteAll()
        }
    }

    @Test
    fun `findByPlayerId returns empty list before init`() {
        assertTrue(playerLandmarkDao.findByPlayerId(playerId).isEmpty())
    }

    @Test
    fun `findAll returns all landmark entries`() {
        playerLandmarkDao.initForPlayer(playerId)
        assertEquals(LandmarkType.entries.size, playerLandmarkDao.findAll().size)
    }

    @Test
    fun `findAll returns empty list before any init`() {
        assertTrue(playerLandmarkDao.findAll().isEmpty())
    }

    @Test
    fun `initForPlayer creates one unbuilt entry per landmark type`() {
        playerLandmarkDao.initForPlayer(playerId)
        val landmarks = playerLandmarkDao.findByPlayerId(playerId)
        assertEquals(LandmarkType.entries.size, landmarks.size)
        assertTrue(landmarks.none { it.isBuilt })
    }

    @Test
    fun `findByPlayerIdAndType returns correct landmark after init`() {
        playerLandmarkDao.initForPlayer(playerId)
        val landmark = playerLandmarkDao.findByPlayerIdAndType(playerId, LandmarkType.TRAIN_STATION)
        assertNotNull(landmark)
        assertEquals(LandmarkType.TRAIN_STATION, landmark!!.landmarkType)
        assertFalse(landmark.isBuilt)
    }

    @Test
    fun `findByPlayerIdAndType returns null before init`() {
        assertNull(playerLandmarkDao.findByPlayerIdAndType(playerId, LandmarkType.TRAIN_STATION))
    }

    @Test
    fun `markBuilt sets isBuilt to true for correct landmark`() {
        playerLandmarkDao.initForPlayer(playerId)
        playerLandmarkDao.markBuilt(playerId, LandmarkType.TRAIN_STATION)
        assertTrue(playerLandmarkDao.findByPlayerIdAndType(playerId, LandmarkType.TRAIN_STATION)!!.isBuilt)
    }

    @Test
    fun `markBuilt does not affect other landmarks`() {
        playerLandmarkDao.initForPlayer(playerId)
        playerLandmarkDao.markBuilt(playerId, LandmarkType.TRAIN_STATION)
        assertFalse(playerLandmarkDao.findByPlayerIdAndType(playerId, LandmarkType.SHOPPING_MALL)!!.isBuilt)
    }

    @Test
    fun `markBuilt throws LandmarkNotFoundException when landmark type does not exist in database`() {
        transaction { Landmarks.deleteAll() }
        assertThrows<LandmarkNotFoundException> {
            playerLandmarkDao.markBuilt(playerId, LandmarkType.TRAIN_STATION)
        }
    }

    @Test
    fun `allBuilt returns false when only some landmarks are built`() {
        playerLandmarkDao.initForPlayer(playerId)
        playerLandmarkDao.markBuilt(playerId, LandmarkType.TRAIN_STATION)
        assertFalse(playerLandmarkDao.allBuilt(playerId))
    }

    @Test
    fun `allBuilt returns true when every landmark is built`() {
        playerLandmarkDao.initForPlayer(playerId)
        LandmarkType.entries.forEach { playerLandmarkDao.markBuilt(playerId, it) }
        assertTrue(playerLandmarkDao.allBuilt(playerId))
    }

    @Test
    fun `delete removes specific landmark from player`() {
        playerLandmarkDao.initForPlayer(playerId)
        playerLandmarkDao.delete(playerId, LandmarkType.TRAIN_STATION)
        assertNull(playerLandmarkDao.findByPlayerIdAndType(playerId, LandmarkType.TRAIN_STATION))
    }

    @Test
    fun `delete throws LandmarkNotFoundException when landmark type does not exist in database`() {
        transaction { Landmarks.deleteAll() }
        assertThrows<LandmarkNotFoundException> {
            playerLandmarkDao.delete(playerId, LandmarkType.RADIO_TOWER)
        }
    }
}