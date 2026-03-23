package org.machikoro.server.controller

import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.exception.CustomWebSocketException
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.stereotype.Controller
import org.slf4j.LoggerFactory

@Controller
class WebSocketController {
    private val logger = LoggerFactory.getLogger(WebSocketController::class.java)

    /**
     * Handle incoming chat messages and broadcast to all subscribers.
     * Message is sent to /app/chat.send and broadcast to /topic/public
     *
     * @throws CustomWebSocketException if message validation fails
     */
    @MessageMapping("/chat.send")
    @SendTo("/topic/public")
    fun sendMessage(@Payload message: WebSocketMessage): WebSocketMessage {
        try {
            if (message.sender.isBlank()) {
                throw CustomWebSocketException(
                    code = "INVALID_SENDER",
                    message = "Sender name cannot be empty"
                )
            }
            if (message.content.isNullOrBlank()) {
                throw CustomWebSocketException(
                    code = "INVALID_MESSAGE",
                    message = "Message content cannot be empty"
                )
            }
            logger.info("Message received from ${message.sender}: ${message.content}")
            return message
        } catch (ex: CustomWebSocketException) {
            logger.error("Validation error: ${ex.message}")
            throw ex
        } catch (ex: Exception) {
            logger.error("Unexpected error in sendMessage: ${ex.message}", ex)
            throw CustomWebSocketException(
                code = "SEND_MESSAGE_ERROR",
                message = "Failed to process message",
                cause = ex
            )
        }
    }

    /**
     * Handle user join events and broadcast to all subscribers.
     * Stores username in session for later reference during disconnect.
     *
     * @throws CustomWebSocketException if user validation fails
     */
    @MessageMapping("/chat.addUser")
    @SendTo("/topic/public")
    fun addUser(@Payload message: WebSocketMessage, headerAccessor: SimpMessageHeaderAccessor): WebSocketMessage {
        try {
            if (message.sender.isBlank()) {
                throw CustomWebSocketException(
                    code = "INVALID_USERNAME",
                    message = "Username cannot be empty"
                )
            }
            logger.info("User ${message.sender} joined the chat")
            headerAccessor.sessionAttributes?.put("username", message.sender)
            return message
        } catch (ex: CustomWebSocketException) {
            logger.error("User validation error: ${ex.message}")
            throw ex
        } catch (ex: Exception) {
            logger.error("Unexpected error in addUser: ${ex.message}", ex)
            throw CustomWebSocketException(
                code = "ADD_USER_ERROR",
                message = "Failed to add user",
                cause = ex
            )
        }
    }
}
