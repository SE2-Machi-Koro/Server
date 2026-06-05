package org.machikoro.server.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.DuplicateUserException
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.InvalidCredentialsException
import org.machikoro.server.exception.InvalidSessionTokenException
import org.machikoro.server.exception.NotAdminException
import org.machikoro.server.exception.NotHostException
import org.machikoro.server.exception.NotInGameException
import org.springframework.http.HttpStatus

class RestExceptionHandlerTest {

    private val handler = RestExceptionHandler()

    @Test
    fun `maps duplicate user to bad request`() {
        val response = handler.handleDuplicateUser(DuplicateUserException("Username 'alice' is already taken"))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Username 'alice' is already taken", response.body)
    }

    @Test
    fun `maps invalid credentials to unauthorized`() {
        val response = handler.handleInvalidCredentials(InvalidCredentialsException("Invalid username or password"))

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertEquals("Invalid username or password", response.body)
    }

    @Test
    fun `maps admin authentication failures to unauthorized and forbidden`() {
        val invalidToken = handler.handleInvalidSessionToken(InvalidSessionTokenException("Invalid session token"))
        val notAdmin = handler.handleNotAdmin(NotAdminException("Admin access required"))

        assertEquals(HttpStatus.UNAUTHORIZED, invalidToken.statusCode)
        assertEquals("Invalid session token", invalidToken.body)
        assertEquals(HttpStatus.FORBIDDEN, notAdmin.statusCode)
        assertEquals("Admin access required", notAdmin.body)
    }

    @Test
    fun `maps game authorization and membership failures`() {
        val notHost = handler.handleNotHost(NotHostException("User 2 is not the host"))
        val notInGame = handler.handleNotInGame(NotInGameException("Caller is not a player"))

        assertEquals(HttpStatus.FORBIDDEN, notHost.statusCode)
        assertEquals("User 2 is not the host", notHost.body)
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, notInGame.statusCode)
        assertEquals("Caller is not a player", notInGame.body)
    }

    @Test
    fun `maps game not found to not found`() {
        val response = handler.handleGameNotFound(GameNotFoundException("Game 404 not found"))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("Game 404 not found", response.body)
    }

    @Test
    fun `maps custom domain errors to bad request`() {
        val response = handler.handleDomainException(CustomWebSocketException("GAME_NOT_STARTED", "Game has not started"))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Game has not started", response.body)
    }

    @Test
    fun `maps unexpected exceptions to internal server error without leaking details`() {
        val response = handler.handleUnexpected(IllegalStateException("database password leaked"))

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertNull(response.body)
    }
}
