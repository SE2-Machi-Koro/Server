package org.machikoro.server.dto

data class ApiErrorResponse(
    val code: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
)
