package org.machikoro.server.handler

import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.DuplicateUserException
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.InvalidCredentialsException
import org.machikoro.server.exception.InvalidSessionTokenException
import org.machikoro.server.exception.NotAdminException
import org.machikoro.server.exception.NotHostException
import org.machikoro.server.exception.NotInGameException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class RestExceptionHandler {
    private val logger = LoggerFactory.getLogger(RestExceptionHandler::class.java)

    @ExceptionHandler(DuplicateUserException::class)
    fun handleDuplicateUser(ex: DuplicateUserException): ResponseEntity<String> =
        badRequest(ex)

    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(ex: InvalidCredentialsException): ResponseEntity<String> =
        status(HttpStatus.UNAUTHORIZED, ex)

    @ExceptionHandler(InvalidSessionTokenException::class)
    fun handleInvalidSessionToken(ex: InvalidSessionTokenException): ResponseEntity<String> =
        status(HttpStatus.UNAUTHORIZED, ex)

    @ExceptionHandler(NotAdminException::class)
    fun handleNotAdmin(ex: NotAdminException): ResponseEntity<String> =
        status(HttpStatus.FORBIDDEN, ex)

    @ExceptionHandler(NotHostException::class)
    fun handleNotHost(ex: NotHostException): ResponseEntity<String> =
        status(HttpStatus.FORBIDDEN, ex)

    @ExceptionHandler(GameNotFoundException::class)
    fun handleGameNotFound(ex: GameNotFoundException): ResponseEntity<String> =
        status(HttpStatus.NOT_FOUND, ex)

    @ExceptionHandler(NotInGameException::class)
    fun handleNotInGame(ex: NotInGameException): ResponseEntity<String> =
        status(HttpStatus.UNPROCESSABLE_ENTITY, ex)

    @ExceptionHandler(CustomWebSocketException::class)
    fun handleDomainException(ex: CustomWebSocketException): ResponseEntity<String> {
        logger.warn("REST domain exception [{}]: {}", ex.errorCode, ex.message)
        return ResponseEntity.badRequest().body(ex.message)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<Void> {
        logger.error("Unhandled REST exception", ex)
        return ResponseEntity.internalServerError().build()
    }

    private fun badRequest(ex: RuntimeException): ResponseEntity<String> =
        status(HttpStatus.BAD_REQUEST, ex)

    private fun status(status: HttpStatus, ex: RuntimeException): ResponseEntity<String> {
        logger.warn("REST request rejected with {}: {}", status.value(), ex.message)
        return ResponseEntity.status(status).body(ex.message ?: "Request rejected")
    }
}
