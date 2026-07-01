package org.machikoro.server.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.machikoro.server.auth.StompAuthChannelInterceptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.config.SimpleBrokerRegistration
import org.springframework.web.socket.config.annotation.SockJsServiceRegistration
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration
import org.junit.jupiter.api.Assertions.assertTrue
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.models.PlayerLandmarkModel
import org.springframework.messaging.MessageHeaders
import org.springframework.messaging.converter.JacksonJsonMessageConverter
import org.springframework.util.MimeTypeUtils
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class WebSocketConfigTests {

    private val authInterceptor = mock(StompAuthChannelInterceptor::class.java)
    // KotlinModule preserves is-prefixed boolean names (isBuilt, isReady) — mirrors production setup.
    private val jsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val config = WebSocketConfig(authInterceptor, jsonMapper).apply {
        allowedOrigins = "http://localhost:8080,http://localhost:3000"
    }

    @Test
    fun configureMessageBrokerShouldEnableExpectedDestinations() {
        val registry = mock(MessageBrokerRegistry::class.java)
        val brokerRegistration = mock(SimpleBrokerRegistration::class.java)
        `when`(registry.enableSimpleBroker("/topic", "/queue")).thenReturn(brokerRegistration)
        `when`(brokerRegistration.setHeartbeatValue(any())).thenReturn(brokerRegistration)
        `when`(brokerRegistration.setTaskScheduler(any())).thenReturn(brokerRegistration)

        config.configureMessageBroker(registry)

        verify(registry).enableSimpleBroker("/topic", "/queue")
        verify(registry).setApplicationDestinationPrefixes("/app")
        verify(brokerRegistration).setTaskScheduler(any())
        verify(brokerRegistration).setHeartbeatValue(
            org.mockito.kotlin.check {
                assertArrayEquals(
                    longArrayOf(
                        WebSocketConfig.HEARTBEAT_INTERVAL_MS,
                        WebSocketConfig.HEARTBEAT_INTERVAL_MS,
                    ),
                    it,
                )
            },
        )
    }

    @Test
    fun brokerHeartbeatSchedulerShouldBeInitialized() {
        val scheduler = config.brokerHeartbeatScheduler() as org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
        scheduler.initialize()
        try {
            scheduler.schedule({ }, java.time.Instant.now())
        } finally {
            scheduler.destroy()
        }
    }

    @Test
    fun registerStompEndpointsShouldConfigureNativeAndSockJsEndpoints() {
        val registry = mock(StompEndpointRegistry::class.java)
        val nativeEndpointRegistration = mock(StompWebSocketEndpointRegistration::class.java)
        val sockJsEndpointRegistration = mock(StompWebSocketEndpointRegistration::class.java)
        val sockJsRegistration = mock(SockJsServiceRegistration::class.java)

        `when`(registry.addEndpoint(WebSocketConfig.NATIVE_WS_ENDPOINT)).thenReturn(nativeEndpointRegistration)
        `when`(registry.addEndpoint(WebSocketConfig.SOCKJS_WS_ENDPOINT)).thenReturn(sockJsEndpointRegistration)
        `when`(nativeEndpointRegistration.setAllowedOrigins("http://localhost:8080", "http://localhost:3000")).thenReturn(nativeEndpointRegistration)
        `when`(sockJsEndpointRegistration.setAllowedOrigins("http://localhost:8080", "http://localhost:3000")).thenReturn(sockJsEndpointRegistration)
        `when`(sockJsEndpointRegistration.withSockJS()).thenReturn(sockJsRegistration)
        `when`(sockJsRegistration.setDisconnectDelay(5_000)).thenReturn(sockJsRegistration)
        `when`(sockJsRegistration.setHeartbeatTime(25_000)).thenReturn(sockJsRegistration)

        config.registerStompEndpoints(registry)

        verify(registry).addEndpoint(WebSocketConfig.NATIVE_WS_ENDPOINT)
        verify(registry).addEndpoint(WebSocketConfig.SOCKJS_WS_ENDPOINT)
        verify(nativeEndpointRegistration).setAllowedOrigins("http://localhost:8080", "http://localhost:3000")
        verify(sockJsEndpointRegistration).setAllowedOrigins("http://localhost:8080", "http://localhost:3000")
        verify(sockJsEndpointRegistration).withSockJS()
        verify(sockJsRegistration).setDisconnectDelay(5_000)
        verify(sockJsRegistration).setHeartbeatTime(25_000)
    }

    @Test
    fun configureClientInboundChannelRegistersAuthInterceptor() {
        val registration = mock(ChannelRegistration::class.java)

        config.configureClientInboundChannel(registration)

        verify(registration).interceptors(authInterceptor)
    }

    @Test
    fun configureMessageConvertersShouldPreserveIsPrefixedBooleanNames() {
        val converters = mutableListOf<org.springframework.messaging.converter.MessageConverter>()
        config.configureMessageConverters(converters)
        val converter = converters.filterIsInstance<JacksonJsonMessageConverter>().single()

        // Serialize through the actual converter to catch any regression in the global fix.
        val headers = MessageHeaders(mapOf(MessageHeaders.CONTENT_TYPE to MimeTypeUtils.parseMimeType("application/json")))
        val message = converter.toMessage(
            PlayerLandmarkModel(playerId = 1, landmarkType = LandmarkType.TRAIN_STATION, isBuilt = true),
            headers,
        )
        val json = String(message!!.payload as ByteArray)

        assertTrue("\"isBuilt\"" in json, "Expected isBuilt key in JSON but got: $json")
    }
}
