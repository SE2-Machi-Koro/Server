package org.machikoro.server.config

import org.machikoro.server.auth.StompAuthChannelInterceptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val authInterceptor: StompAuthChannelInterceptor,
) : WebSocketMessageBrokerConfigurer {

    companion object {
        const val NATIVE_WS_ENDPOINT = "/ws"
        const val SOCKJS_WS_ENDPOINT = "/ws-sockjs"
    }

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
     * Registers a native WebSocket endpoint for Android clients and a separate SockJS endpoint
     * for browser/testing flows that still rely on fallback transports.
     * Allowed origins are configured via the 'websocket.allowed-origins' property.
     *
     * SockJS tuning:
     * - `setDisconnectDelay`: how long the server waits before treating a SockJS session as
     *   disconnected after the underlying transport closes. Lowered from the 5 s default to
     *   2 s so stale server-side session objects are released sooner.
     * - `setHeartbeatTime`: interval at which the SockJS server sends heartbeat frames to keep
     *   the transport alive through proxies. Set to 25 s (SockJS spec recommendation).
     */
    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        val origins = allowedOrigins.split(",").map { it.trim() }.toTypedArray()
        registry.addEndpoint(NATIVE_WS_ENDPOINT)
            .setAllowedOrigins(*origins)

        registry.addEndpoint(SOCKJS_WS_ENDPOINT)
            .setAllowedOrigins(*origins)
            .withSockJS()
            .setDisconnectDelay(2_000)
            .setHeartbeatTime(25_000)
    }

    /**
     * Register STOMP-layer auth on the inbound channel. The interceptor
     * validates the session token on every CONNECT frame and rejects
     * unauthenticated handshakes — see [StompAuthChannelInterceptor].
     */
    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(authInterceptor)
    }
}
