package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.dto.RegisterRequest
import org.machikoro.server.dto.RegisterResponse
import org.machikoro.server.exception.DuplicateUserException
import org.machikoro.server.service.AuthService
import org.mockito.kotlin.mock
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
}
