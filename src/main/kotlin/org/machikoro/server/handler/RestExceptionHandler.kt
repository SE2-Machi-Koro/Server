package org.machikoro.server.handler

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
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class RestExceptionHandler {
    private val logger = LoggerFactory.getLogger(RestExceptionHandler::class.java)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiErrorResponse> =
        errorResponse(
            status = HttpStatus.BAD_REQUEST,
            code = "VALIDATION_FAILED",
            message = ex.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: INVALID_REQUEST,
        )

    @ExceptionHandler(DuplicateUserException::class)
    fun handleBadRequest(ex: DuplicateUserException): ResponseEntity<ApiErrorResponse> =
        errorResponse(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.safeMessage())

    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(ex: InvalidCredentialsException): ResponseEntity<ApiErrorResponse> {
        logger.warn("REST authentication failed: invalid credentials")
        return errorResponse(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", ex.safeMessage())
    }

    @ExceptionHandler(InvalidSessionTokenException::class)
    fun handleInvalidSessionToken(ex: InvalidSessionTokenException): ResponseEntity<ApiErrorResponse> =
        errorResponse(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", ex.safeMessage())

    @ExceptionHandler(NotAdminException::class)
    fun handleNotAdmin(ex: NotAdminException): ResponseEntity<ApiErrorResponse> =
        errorResponse(HttpStatus.FORBIDDEN, "NOT_ADMIN", ex.safeMessage())

    @ExceptionHandler(NotHostException::class)
    fun handleNotHost(ex: NotHostException): ResponseEntity<ApiErrorResponse> =
        errorResponse(HttpStatus.FORBIDDEN, "NOT_HOST", ex.safeMessage())

    @ExceptionHandler(NotInGameException::class)
    fun handleForbiddenAction(ex: NotInGameException): ResponseEntity<ApiErrorResponse> =
        errorResponse(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.safeMessage())

    @ExceptionHandler(
        CardNotFoundException::class,
        GameNotFoundException::class,
        LandmarkNotFoundException::class,
        PlayerNotFoundException::class,
        UserNotFoundException::class,
    )
    fun handleNotFound(ex: RuntimeException): ResponseEntity<ApiErrorResponse> =
        errorResponse(HttpStatus.NOT_FOUND, ex.errorCode(), ex.safeMessage())

    @ExceptionHandler(NoHandlerFoundException::class, NoResourceFoundException::class)
    fun handleMvcNotFound(ex: Exception): ResponseEntity<ApiErrorResponse> =
        errorResponse(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.message ?: "Resource not found")

    @ExceptionHandler(CustomWebSocketException::class)
    fun handleDomainException(ex: CustomWebSocketException): ResponseEntity<ApiErrorResponse> =
        errorResponse(HttpStatus.BAD_REQUEST, ex.errorCode, ex.message)

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiErrorResponse> {
        logger.error("Unhandled REST exception", ex)
        return errorResponse(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            code = "INTERNAL_ERROR",
            message = "Unexpected error while processing request",
        )
    }

    private fun errorResponse(
        status: HttpStatus,
        code: String,
        message: String,
    ): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(status).body(ApiErrorResponse(code = code, message = message))

    private fun RuntimeException.safeMessage(): String = message ?: INVALID_REQUEST

    private fun RuntimeException.errorCode(): String =
        when (this) {
            is CardNotFoundException -> "CARD_NOT_FOUND"
            is GameNotFoundException -> errorCode
            is LandmarkNotFoundException -> "LANDMARK_NOT_FOUND"
            is PlayerNotFoundException -> "PLAYER_NOT_FOUND"
            is UserNotFoundException -> "USER_NOT_FOUND"
            else -> "NOT_FOUND"
        }

    private companion object {
        const val INVALID_REQUEST = "Invalid request"
    }
}
