package org.machikoro.server.config

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.SockJsServiceRegistration
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration

class WebSocketConfigTests {

    private val config = WebSocketConfig().apply {
        allowedOrigins = "http://localhost:8080,http://localhost:3000"
    }

    @Test
    fun configureMessageBrokerShouldEnableExpectedDestinations() {
        val registry = mock(MessageBrokerRegistry::class.java)

        config.configureMessageBroker(registry)

        verify(registry).enableSimpleBroker("/topic", "/queue")
        verify(registry).setApplicationDestinationPrefixes("/app")
    }

    @Test
    fun registerStompEndpointsShouldConfigureWsEndpointWithSockJs() {
        val registry = mock(StompEndpointRegistry::class.java)
        val endpointRegistration = mock(StompWebSocketEndpointRegistration::class.java)
        val sockJsRegistration = mock(SockJsServiceRegistration::class.java)

        `when`(registry.addEndpoint("/ws")).thenReturn(endpointRegistration)
        `when`(endpointRegistration.setAllowedOrigins("http://localhost:8080", "http://localhost:3000")).thenReturn(endpointRegistration)
        `when`(endpointRegistration.withSockJS()).thenReturn(sockJsRegistration)

        config.registerStompEndpoints(registry)

        verify(registry).addEndpoint("/ws")
        verify(endpointRegistration).setAllowedOrigins("http://localhost:8080", "http://localhost:3000")
        verify(endpointRegistration).withSockJS()
    }
}

