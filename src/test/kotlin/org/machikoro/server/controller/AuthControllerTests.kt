package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.machikoro.server.dto.LoginRequest
import org.machikoro.server.dto.LoginResponse
import org.machikoro.server.dto.LogoutRequest
import org.machikoro.server.dto.RegisterRequest
import org.machikoro.server.dto.RegisterResponse
import org.machikoro.server.exception.DuplicateUserException
import org.machikoro.server.exception.InvalidCredentialsException
import org.machikoro.server.service.AuthService
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus

class AuthControllerTests {

    private val authService = mock<AuthService>()
    private val controller = AuthController(authService)

    @Test
    fun `register returns ok with response body when service succeeds`() {
        val request = RegisterRequest(username = "alice", password = "hunter2")
        val expected = RegisterResponse(id = 42, username = "alice")
        whenever(authService.register("alice", "hunter2")).thenReturn(expected)

        val response = controller.register(request)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(expected, response.body)
    }

    @Test
    fun `register returns bad request when username already taken`() {
        val request = RegisterRequest(username = "alice", password = "hunter2")
        whenever(authService.register("alice", "hunter2"))
            .thenThrow(DuplicateUserException("Username 'alice' is already taken"))

        val response = controller.register(request)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Username 'alice' is already taken", response.body)
    }

    @Test
    fun `register returns bad request on validation failure`() {
        val request = RegisterRequest(username = "", password = "hunter2")
        whenever(authService.register("", "hunter2"))
            .thenThrow(IllegalArgumentException("Username must not be blank"))

        val response = controller.register(request)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Username must not be blank", response.body)
    }

    @Test
    fun `login returns ok with response body when service succeeds`() {
        val request = LoginRequest(username = "alice", password = "hunter2")
        val expected = LoginResponse(sessionToken = "token-uuid", username = "alice", userId = 42) // NEU
        whenever(authService.login("alice", "hunter2")).thenReturn(expected)

        val response = controller.login(request)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(expected, response.body)
    }

    @Test
    fun `login returns 401 with the generic body on InvalidCredentialsException`() {
        val request = LoginRequest(username = "alice", password = "wrong")
        whenever(authService.login("alice", "wrong"))
            .thenThrow(InvalidCredentialsException("Invalid username or password"))

        val response = controller.login(request)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertEquals("Invalid username or password", response.body)
    }

    @Test
    fun `login returns bad request on validation failure`() {
        val request = LoginRequest(username = "", password = "hunter2")
        whenever(authService.login("", "hunter2"))
            .thenThrow(IllegalArgumentException("Username must not be blank"))

        val response = controller.login(request)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Username must not be blank", response.body)
    }

    @Test
    fun `logout returns ok on success`() {
        val request = LogoutRequest(sessionToken = "token-uuid")

        val response = controller.logout(request)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNull(response.body)
        verify(authService).logout("token-uuid")
    }

    @Test
    fun `logout returns ok even when token is unknown`() {
        // Service is a no-op for unknown tokens — controller should still return 200.
        val request = LogoutRequest(sessionToken = "stale")

        val response = controller.logout(request)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNull(response.body)
        verify(authService).logout("stale")
    }

    @Test
    fun `login response is identical for unknown user and wrong password`() {
        // Locks down the no-enumeration contract at the HTTP boundary, not just inside
        // the service. Both paths must produce the same status code AND the same body.
        whenever(authService.login("ghost", "any"))
            .thenThrow(InvalidCredentialsException("Invalid username or password"))
        whenever(authService.login("alice", "wrong"))
            .thenThrow(InvalidCredentialsException("Invalid username or password"))

        val unknownUserResponse = controller.login(LoginRequest("ghost", "any"))
        val wrongPasswordResponse = controller.login(LoginRequest("alice", "wrong"))

        assertEquals(unknownUserResponse.statusCode, wrongPasswordResponse.statusCode)
        assertEquals(unknownUserResponse.body, wrongPasswordResponse.body)
        assertEquals(HttpStatus.UNAUTHORIZED, unknownUserResponse.statusCode)
    }
}
