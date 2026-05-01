package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.GameStartedException
import org.machikoro.server.exception.LobbyFullException
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LobbyServiceTest {

    private val gameDao = mock<GameDao>()
    private val playerDao = mock<PlayerDao>()
    private val lobbyService = LobbyService(gameDao, playerDao)

    private fun game(id: Int, status: GameStatus) =
        GameModel(
            id = id,
            status = status,
            hostUserId = 1,
            lobbyCode = "ABC123",
            maxPlayers = 4,
            currentTurnIndex = 0,
            turnPhase = org.machikoro.server.domain.enums.TurnPhase.ROLL_DICE,
            lastDiceRoll = null,
            hasPurchasedThisTurn = false,
            roundNumber = 1
        )

    private fun player(id: Int) =
        PlayerModel(id = id, gameId = 1, userId = id, turnOrder = 0, coins = 3, lastSeenAt = 30)

    @Test
    fun `addUserToLobby adds player successfully`() {
        val gameId = 1
        val userId = 10
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.getPlayers(gameId)).thenReturn(emptyList())
        whenever(playerDao.addPlayer(gameId, userId)).thenReturn(player(1))

        val result = lobbyService.addUserToLobby(gameId, userId)

        assertNotNull(result)
        verify(playerDao).addPlayer(gameId, userId)
    }

    @Test
    fun `addUserToLobby throws GameNotFoundException`() {
        whenever(gameDao.findById(any())).thenReturn(null)

        assertThrows<GameNotFoundException> {
            lobbyService.addUserToLobby(1, 10)
        }
    }

    @Test
    fun `addUserToLobby throws GameStartedException`() {
        val gameId = 2
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.IN_PROGRESS))

        assertThrows<GameStartedException> {
            lobbyService.addUserToLobby(gameId, 10)
        }
    }

    @Test
    fun `addUserToLobby throws LobbyFullException`() {
        val gameId = 3
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.getPlayers(gameId)).thenReturn(
            listOf(player(1), player(2), player(3), player(4))
        )

        assertThrows<LobbyFullException> {
            lobbyService.addUserToLobby(gameId, 10)
        }
    }

    @Test
    fun `createLobby creates game and adds host as player`() {
        val hostUserId = 10
        val gameId = 1
        val createdGame = game(gameId, GameStatus.WAITING).copy(hostUserId = hostUserId)

        whenever(gameDao.create(any(), any(), any())).thenReturn(gameId)
        whenever(playerDao.addPlayer(gameId, hostUserId)).thenReturn(player(1))
        whenever(gameDao.findById(gameId)).thenReturn(createdGame)

        val result = lobbyService.createLobby(hostUserId)

        assertEquals(gameId, result.id)
        assertEquals(hostUserId, result.hostUserId)
        assertEquals(GameStatus.WAITING, result.status)

        verify(gameDao).create(eq(hostUserId), any<String>(), eq(4))
        verify(playerDao).addPlayer(gameId, hostUserId)
        verify(gameDao).findById(gameId)
    }

    @Test
    fun `createLobby throws GameNotFoundException when created game cannot be found`() {
        val hostUserId = 10
        val gameId = 1

        whenever(gameDao.create(hostUserId = hostUserId)).thenReturn(gameId)
        whenever(playerDao.addPlayer(gameId, hostUserId)).thenReturn(player(1))
        whenever(gameDao.findById(gameId)).thenReturn(null)

        assertThrows<GameNotFoundException> {
            lobbyService.createLobby(hostUserId)
        }
    }

    @Test
    fun `startGame sets status to in progress`() {
        val gameId = 4
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))

        val result = lobbyService.startGame(gameId)

        assertEquals(GameStatus.IN_PROGRESS, result.status)
        verify(gameDao).updateStatus(gameId, GameStatus.IN_PROGRESS)
    }

    @Test
    fun `startGame throws GameNotFoundException`() {
        whenever(gameDao.findById(any())).thenReturn(null)

        assertThrows<GameNotFoundException> {
            lobbyService.startGame(1)
        }
    }
}
