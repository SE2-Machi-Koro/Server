package org.machikoro.server.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.machikoro.server.SpringBootTestWithoutDataSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTestWithoutDataSource
@AutoConfigureMockMvc
@TestPropertySource(properties = [
    "spring.flyway.enabled=false"
])
class SecurityConfigTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var securityFilterChain: SecurityFilterChain

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Test
    fun securityFilterChainBeanShouldBeCreated() {
        assertNotNull(securityFilterChain)
    }

    @Test
    fun passwordEncoderBeanShouldBeCreated() {
        assertNotNull(passwordEncoder)
    }

    @Test
    fun passwordEncoderProducesDifferentHashesForSamePassword() {
        val raw = "correct-horse-battery-staple"
        val first = passwordEncoder.encode(raw)
        val second = passwordEncoder.encode(raw)
        assertNotEquals(first, second)
    }

    @Test
    fun passwordEncoderMatchesEncodedPasswordsAndRejectsWrongOnes() {
        val raw = "correct-horse-battery-staple"
        val hash = passwordEncoder.encode(raw)
        assertTrue(passwordEncoder.matches(raw, hash))
        assertFalse(passwordEncoder.matches("wrong-password", hash))
    }

    @Test
    fun apiGetRequestsShouldRequireAuthentication() {
        mockMvc.perform(get("/api/non-existing"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun apiPostRequestsWithoutCsrfShouldBeForbidden() {
        mockMvc.perform(post("/api/non-existing"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun websocketEndpointShouldNotRequireAuthentication() {
        mockMvc.perform(get("/ws/non-existing"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun nativeWebsocketEndpointShouldNotRequireAuthentication() {
        mockMvc.perform(get("/ws-sockjs/non-existing"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun websocketPostWithoutCsrfShouldBePermitted() {
        // SockJS uses POST for xhr_send; CSRF is disabled for /ws/** to allow this
        mockMvc.perform(post("/ws-sockjs/non-existing"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun rootPathShouldBeAccessibleWithoutAuthentication() {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk)
    }

    @Test
    fun indexHtmlShouldBeAccessibleWithoutAuthentication() {
        mockMvc.perform(get("/index.html"))
            .andExpect(status().isOk)
    }

    @Test
    fun staticResourcePathsShouldBePermittedWithoutAuthentication() {
        mockMvc.perform(get("/css/nonexistent.css"))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/js/nonexistent.js"))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/webjars/nonexistent"))
            .andExpect(status().isNotFound)
    }
}