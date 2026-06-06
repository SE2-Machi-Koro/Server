package org.machikoro.server.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.machikoro.server.dto.ApiErrorResponse
import org.machikoro.server.exception.CardNotFoundException
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.DuplicateUserException
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.InvalidCredentialsException
import org.machikoro.server.exception.InvalidSessionTokenException
import org.machikoro.server.exception.LandmarkNotFoundException
import org.machikoro.server.exception.NotAdminException
import org.machikoro.server.exception.NotHostException
import org.machikoro.server.exception.NotInGameException
import org.machikoro.server.exception.PlayerNotFoundException
import org.machikoro.server.exception.UserNotFoundException
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.BindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException

class RestExceptionHandlerTest {

    private val handler = RestExceptionHandler()

    private fun exceptionWith(fieldErrors: List<FieldError>): MethodArgumentNotValidException {
        val bindingResult = mock<BindingResult>()
        whenever(bindingResult.fieldErrors).thenReturn(fieldErrors)
        val ex = mock<MethodArgumentNotValidException>()
        whenever(ex.bindingResult).thenReturn(bindingResult)
        return ex
    }

    @Test
    fun `returns validation error response for first field error message`() {
        val ex = exceptionWith(listOf(FieldError("loginRequest", "username", "Username must not be blank")))

        val response = handler.handleValidation(ex)

        assertApiError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Username must not be blank")
    }

    @Test
    fun `falls back to generic validation message when there are no field errors`() {
        val ex = exceptionWith(emptyList())

        val response = handler.handleValidation(ex)

        assertApiError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid request")
    }

    @Test
    fun `falls back to generic validation message when field error has no default message`() {
        val fieldError = mock<FieldError>()
        whenever(fieldError.defaultMessage).thenReturn(null)
        val ex = exceptionWith(listOf(fieldError))

        val response = handler.handleValidation(ex)

        assertApiError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid request")
    }

    @Test
    fun `maps invalid credentials to 401`() {
        val response = handler.handleInvalidCredentials(InvalidCredentialsException("Invalid username or password"))

        assertApiError(response, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password")
    }

    @Test
    fun `maps invalid session token to 401`() {
        val response = handler.handleInvalidSessionToken(InvalidSessionTokenException("Missing Authorization header"))

        assertApiError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Missing Authorization header")
    }

    @Test
    fun `maps not admin to 403`() {
        val response = handler.handleNotAdmin(NotAdminException("Admin access required"))

        assertApiError(response, HttpStatus.FORBIDDEN, "NOT_ADMIN", "Admin access required")
    }

    @Test
    fun `maps not host to 403`() {
        val response = handler.handleNotHost(NotHostException("Only the host can start this game"))

        assertApiError(response, HttpStatus.FORBIDDEN, "NOT_HOST", "Only the host can start this game")
    }

    @Test
    fun `maps forbidden gameplay action to 403`() {
        val response = handler.handleForbiddenAction(NotInGameException("Caller is not a player in the game"))

        assertApiError(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Caller is not a player in the game")
    }

    @Test
    fun `maps known not found exceptions to 404`() {
        listOf(
            CardNotFoundException("Card not found") to "CARD_NOT_FOUND",
            GameNotFoundException("Game not found") to "GAME_NOT_FOUND",
            LandmarkNotFoundException("Landmark not found") to "LANDMARK_NOT_FOUND",
            PlayerNotFoundException("Player not found") to "PLAYER_NOT_FOUND",
            UserNotFoundException("User not found") to "USER_NOT_FOUND",
        ).forEach { (exception, code) ->
            assertApiError(handler.handleNotFound(exception), HttpStatus.NOT_FOUND, code, exception.message!!)
        }
    }

    @Test
    fun `maps duplicate user to 400`() {
        val response = handler.handleBadRequest(DuplicateUserException("Username 'alice' is already taken"))

        assertApiError(response, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Username 'alice' is already taken")
    }

    @Test
    fun `maps domain websocket exception used by REST services to 400`() {
        val response = handler.handleDomainException(CustomWebSocketException("NOT_ENOUGH_PLAYERS", "Need at least two players"))

        assertApiError(response, HttpStatus.BAD_REQUEST, "NOT_ENOUGH_PLAYERS", "Need at least two players")
    }

    @Test
    fun `maps unexpected exceptions to generic 500 response`() {
        val response = handler.handleUnexpected(IllegalStateException("database unavailable"))

        assertApiError(
            response,
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "Unexpected error while processing request",
        )
    }

    private fun assertApiError(
        response: ResponseEntity<ApiErrorResponse>,
        expectedStatus: HttpStatus,
        expectedCode: String,
        expectedMessage: String,
    ) {
        assertEquals(expectedStatus, response.statusCode)
        assertNotNull(response.body)
        val body = response.body!!
        assertEquals(expectedCode, body.code)
        assertEquals(expectedMessage, body.message)
        assertTrue(body.timestamp > 0)
    }
}
