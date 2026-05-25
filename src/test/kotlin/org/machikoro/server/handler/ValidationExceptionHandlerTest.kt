package org.machikoro.server.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.validation.BindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException

/**
 * Unit-level coverage for [ValidationExceptionHandler]'s null-handling branches.
 * The happy path (a field error carrying a message) is exercised end-to-end by
 * [org.machikoro.server.controller.AuthValidationTest]; here we cover the
 * fallbacks a real `@Valid` failure can't easily reproduce — no field errors,
 * and a field error with a null message.
 */
class ValidationExceptionHandlerTest {

    private val handler = ValidationExceptionHandler()

    private fun exceptionWith(fieldErrors: List<FieldError>): MethodArgumentNotValidException {
        val bindingResult = mock<BindingResult>()
        whenever(bindingResult.fieldErrors).thenReturn(fieldErrors)
        val ex = mock<MethodArgumentNotValidException>()
        whenever(ex.bindingResult).thenReturn(bindingResult)
        return ex
    }

    @Test
    fun `returns first field error message`() {
        val ex = exceptionWith(listOf(FieldError("loginRequest", "username", "Username must not be blank")))

        val response = handler.handleValidation(ex)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Username must not be blank", response.body)
    }

    @Test
    fun `falls back to generic message when there are no field errors`() {
        val ex = exceptionWith(emptyList())

        val response = handler.handleValidation(ex)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Invalid request", response.body)
    }

    @Test
    fun `falls back to generic message when field error has no default message`() {
        val fieldError = mock<FieldError>()
        whenever(fieldError.defaultMessage).thenReturn(null)
        val ex = exceptionWith(listOf(fieldError))

        val response = handler.handleValidation(ex)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Invalid request", response.body)
    }
}
