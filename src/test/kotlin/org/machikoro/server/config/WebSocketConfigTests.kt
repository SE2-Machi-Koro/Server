package org.machikoro.server.config

import org.junit.jupiter.api.Test
import org.machikoro.server.auth.StompAuthChannelInterceptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.SockJsServiceRegistration
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration

class WebSocketConfigTests {

    private val authInterceptor = mock(StompAuthChannelInterceptor::class.java)
    private val config = WebSocketConfig(authInterceptor).apply {
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
        `when`(sockJsRegistration.setDisconnectDelay(2_000)).thenReturn(sockJsRegistration)
        `when`(sockJsRegistration.setHeartbeatTime(25_000)).thenReturn(sockJsRegistration)

        config.registerStompEndpoints(registry)

        verify(registry).addEndpoint(WebSocketConfig.NATIVE_WS_ENDPOINT)
        verify(registry).addEndpoint(WebSocketConfig.SOCKJS_WS_ENDPOINT)
        verify(nativeEndpointRegistration).setAllowedOrigins("http://localhost:8080", "http://localhost:3000")
        verify(sockJsEndpointRegistration).setAllowedOrigins("http://localhost:8080", "http://localhost:3000")
        verify(sockJsEndpointRegistration).withSockJS()
        verify(sockJsRegistration).setDisconnectDelay(2_000)
        verify(sockJsRegistration).setHeartbeatTime(25_000)
    }

    @Test
    fun configureClientInboundChannelRegistersAuthInterceptor() {
        val registration = mock(ChannelRegistration::class.java)

        config.configureClientInboundChannel(registration)

        verify(registration).interceptors(authInterceptor)
    }
}
