package org.machikoro.server.handler

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Converts JSR-303 (`@Valid`) request-body violations into the plain-text 400
 * body existing clients parse, instead of Spring's default JSON error shape.
 */
@RestControllerAdvice
class ValidationExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<String> =
        ResponseEntity.badRequest()
            .body(ex.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: "Invalid request")
}
