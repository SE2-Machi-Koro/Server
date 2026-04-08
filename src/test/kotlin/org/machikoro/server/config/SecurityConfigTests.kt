package org.machikoro.server.config

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(properties = ["machikoro.db.init.enabled=false"])
@AutoConfigureMockMvc
class SecurityConfigTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var securityFilterChain: SecurityFilterChain

    @Test
    fun securityFilterChainBeanShouldBeCreated() {
        assertNotNull(securityFilterChain)
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
    fun websocketPostWithoutCsrfShouldBePermitted() {
        mockMvc.perform(post("/ws/non-existing"))
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