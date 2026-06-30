package org.machikoro.server.config

import org.machikoro.server.auth.StompAuthChannelInterceptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.converter.ByteArrayMessageConverter
import org.springframework.messaging.converter.JacksonJsonMessageConverter
import org.springframework.messaging.converter.MessageConverter
import org.springframework.messaging.converter.StringMessageConverter
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import tools.jackson.databind.json.JsonMapper

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val authInterceptor: StompAuthChannelInterceptor,
    // Spring Boot's JsonMapper has KotlinModule registered, which preserves
    // is-prefixed boolean property names (e.g. isBuilt, isReady) on the wire.
    private val jsonMapper: JsonMapper,
) : WebSocketMessageBrokerConfigurer {

    companion object {
        const val NATIVE_WS_ENDPOINT = "/ws"
        const val SOCKJS_WS_ENDPOINT = "/ws-sockjs"

        /**
         * Interval (ms) the broker uses for STOMP heartbeats in both directions.
         * Heartbeats keep otherwise-idle WebSocket connections alive so platform
         * proxies (e.g. Railway's edge) do not reap them during a player's
         * think-time, which previously forced reconnects mid-game.
         */
        const val HEARTBEAT_INTERVAL_MS = 10_000L
    }

    @Value("\${websocket.allowed-origins:http://localhost:8080,http://localhost:3000}")
    internal lateinit var allowedOrigins: String

    /**
     * Configure the message broker for pub/sub messaging patterns.
     * Enables the simple in-memory broker for /topic and /queue destinations and sets the
     * application destination prefix.
     *
     * STOMP heartbeats are enabled (both directions) so that idle connections keep producing
     * traffic; without them the simple broker is silent during lulls and the platform proxy
     * closes the socket, forcing clients to reconnect. The simple broker only honours
     * heartbeats when a [TaskScheduler] is supplied, hence [brokerHeartbeatScheduler].
     */
    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        config.enableSimpleBroker("/topic", "/queue")
            .setHeartbeatValue(longArrayOf(HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS))
            .setTaskScheduler(brokerHeartbeatScheduler())
        config.setApplicationDestinationPrefixes("/app")
    }

    /**
     * Dedicated scheduler that drives the simple broker's STOMP heartbeats. Registered as a
     * bean so its lifecycle (start-up/shutdown) is managed with the application context.
     */
    @Bean
    fun brokerHeartbeatScheduler(): TaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("ws-heartbeat-")
        }

    /**
     * Register STOMP endpoints for WebSocket connections.
     * Registers a native WebSocket endpoint for Android clients and a separate SockJS endpoint
     * for browser/testing flows that still rely on fallback transports.
     * Allowed origins are configured via the 'websocket.allowed-origins' property.
     */
    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        val origins = allowedOrigins.split(",").map { it.trim() }.toTypedArray()
        registry.addEndpoint(NATIVE_WS_ENDPOINT)
            .setAllowedOrigins(*origins)

        registry.addEndpoint(SOCKJS_WS_ENDPOINT)
            .setAllowedOrigins(*origins)
            .withSockJS()
            .setDisconnectDelay(5_000)
            .setHeartbeatTime(25_000)
    }

    /**
     * Wire the STOMP JSON converter to Spring Boot's JsonMapper so KotlinModule
     * is active for all WebSocket messages — without this the default converter uses
     * a plain mapper that strips "is" prefixes from boolean getter names.
     */
    override fun configureMessageConverters(messageConverters: MutableList<MessageConverter>): Boolean {
        messageConverters.add(StringMessageConverter())
        messageConverters.add(ByteArrayMessageConverter())
        messageConverters.add(JacksonJsonMessageConverter(jsonMapper))
        return false
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