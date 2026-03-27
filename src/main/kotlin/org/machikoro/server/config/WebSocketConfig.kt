package org.machikoro.server.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig : WebSocketMessageBrokerConfigurer {

    @Value("\${websocket.allowed-origins:http://localhost:8080,http://localhost:3000}")
    internal lateinit var allowedOrigins: String

    /**
     * Configure the message broker for pub/sub messaging patterns.
     * Enables simple in-memory broker for /topic destinations and sets application destination prefix.
     */
    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        config.enableSimpleBroker("/topic", "/queue")
        config.setApplicationDestinationPrefixes("/app")
    }

    /**
     * Register STOMP endpoints for WebSocket connections.
     * Registers the /ws endpoint with CORS configured via property and SockJS fallback support.
     * Allowed origins are configured via the 'websocket.allowed-origins' property.
     */
    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        val origins = allowedOrigins.split(",").map { it.trim() }.toTypedArray()
        registry.addEndpoint("/ws")
            .setAllowedOrigins(*origins)
            .withSockJS()
    }
}

