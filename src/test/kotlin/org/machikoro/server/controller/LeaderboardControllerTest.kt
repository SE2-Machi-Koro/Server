package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.models.UserModel
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LeaderboardControllerTest {

    private val userDao = mock<UserDao>()
    private val controller = LeaderboardController(userDao)

    // Minimal UserModel for leaderboard tests — auth fields irrelevant here
    private fun user(id: Int, username: String, wins: Int, played: Int) = UserModel(
        id = id,
        username = username,
        passwordHash = null,
        sessionToken = null,
        totalWins = wins,
        totalGamesPlayed = played,
    )

    @Test
    fun `getLeaderboard returns ranked entries with correct fields`() {
        whenever(userDao.getLeaderboard(10)).thenReturn(
            listOf(
                user(1, "alice", wins = 10, played = 20),
                user(2, "bob", wins = 5, played = 15),
            )
        )

        val result = controller.getLeaderboard(limit = 10)

        assertEquals(2, result.size)
        assertEquals(1, result[0].rank)
        assertEquals(1, result[0].userId)
        assertEquals("alice", result[0].username)
        assertEquals(10, result[0].totalWins)
        assertEquals(20, result[0].totalGamesPlayed)
        assertEquals(2, result[1].rank)
        assertEquals(2, result[1].userId)
        assertEquals("bob", result[1].username)
    }

    @Test
    fun `getLeaderboard assigns consecutive ranks starting at 1`() {
        whenever(userDao.getLeaderboard(3)).thenReturn(
            listOf(
                user(1, "a", wins = 3, played = 3),
                user(2, "b", wins = 2, played = 2),
                user(3, "c", wins = 1, played = 1),
            )
        )

        val result = controller.getLeaderboard(limit = 3)

        assertEquals(listOf(1, 2, 3), result.map { it.rank })
    }

    @Test
    fun `getLeaderboard passes limit directly to DAO when in range`() {
        whenever(userDao.getLeaderboard(10)).thenReturn(emptyList())

        controller.getLeaderboard(limit = 10)

        verify(userDao).getLeaderboard(10)
    }

    @Test
    fun `getLeaderboard clamps limit below 1 to 1`() {
        whenever(userDao.getLeaderboard(1)).thenReturn(emptyList())

        controller.getLeaderboard(limit = 0)

        verify(userDao).getLeaderboard(1)
    }

    @Test
    fun `getLeaderboard clamps negative limit to 1`() {
        whenever(userDao.getLeaderboard(1)).thenReturn(emptyList())

        controller.getLeaderboard(limit = -50)

        verify(userDao).getLeaderboard(1)
    }

    @Test
    fun `getLeaderboard clamps limit above 100 to 100`() {
        whenever(userDao.getLeaderboard(100)).thenReturn(emptyList())

        controller.getLeaderboard(limit = 200)

        verify(userDao).getLeaderboard(100)
    }

    @Test
    fun `getLeaderboard returns empty list when DAO returns none`() {
        whenever(userDao.getLeaderboard(10)).thenReturn(emptyList())

        val result = controller.getLeaderboard(limit = 10)

        assertTrue(result.isEmpty())
    }
}