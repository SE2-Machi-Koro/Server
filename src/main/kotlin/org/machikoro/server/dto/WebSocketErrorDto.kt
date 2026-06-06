package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema
import org.machikoro.server.exception.CustomWebSocketException

@Schema(description = "Stable error payload emitted by WebSocket endpoints")
data class WebSocketErrorDto(
    @Schema(description = "Stable functional error code", example = "NOT_YOUR_TURN")
    val code: String,

    @Schema(description = "Client-readable error message", example = "It is not your turn")
    val message: String,

    @Schema(description = "Unix timestamp in milliseconds", example = "1714000000000")
    val timestamp: Long = System.currentTimeMillis(),

    @Schema(description = "Optional endpoint-specific metadata; clients must treat code/message/timestamp as the stable contract")
    val context: Map<String, Any?> = emptyMap(),
) {
    companion object {
        private const val INTERNAL_ERROR_CODE = "INTERNAL_ERROR"
        private const val INTERNAL_ERROR_MESSAGE = "Unexpected error while processing WebSocket message"

        fun from(exception: CustomWebSocketException, context: Map<String, Any?> = emptyMap()): WebSocketErrorDto =
            WebSocketErrorDto(
                code = exception.errorCode,
                message = exception.message,
                context = context,
            )

        fun internal(context: Map<String, Any?> = emptyMap()): WebSocketErrorDto =
            WebSocketErrorDto(
                code = INTERNAL_ERROR_CODE,
                message = INTERNAL_ERROR_MESSAGE,
                context = context,
            )
    }
}
