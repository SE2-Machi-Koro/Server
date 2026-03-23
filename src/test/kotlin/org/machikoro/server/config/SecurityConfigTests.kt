package org.machikoro.server.config

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest
class SecurityConfigTests {

    @Autowired
    private lateinit var context: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var securityFilterChain: SecurityFilterChain

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
    }

    @Test
    fun securityFilterChainBeanShouldBeCreated() {
        assertNotNull(securityFilterChain)
    }

    @Test
    fun getRequestsShouldNotRequireAuthentication() {
        mockMvc.perform(get("/api/non-existing"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun postRequestsShouldNotBeBlockedByCsrf() {
        mockMvc.perform(post("/api/non-existing"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun websocketEndpointShouldNotRequireAuthentication() {
        mockMvc.perform(get("/ws/non-existing"))
            .andExpect(status().isNotFound)
    }
}


