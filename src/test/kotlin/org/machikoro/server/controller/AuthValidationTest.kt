package org.machikoro.server.controller

import org.junit.jupiter.api.Test
import org.machikoro.server.SpringBootTestWithoutDataSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Verifies that JSR-303 (`@Valid`) violations on the auth DTOs are rejected at
 * the MVC boundary and surface as a plain-text 400 body (the contract the
 * Android client parses), via [org.machikoro.server.handler.ValidationExceptionHandler].
 */
@SpringBootTestWithoutDataSource
@AutoConfigureMockMvc
@TestPropertySource(properties = [
    "spring.flyway.enabled=false",
    "machikoro.db.init.enabled=false",
])
class AuthValidationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private fun postJson(path: String, body: String): ResultActions =
        mockMvc.perform(
            post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    @Test
    fun `register rejects blank username with plain-text 400`() {
        postJson("/auth/register", """{"username":"","password":"hunter2"}""")
            .andExpect(status().isBadRequest)
            .andExpect(content().string("Username must not be blank"))
    }

    @Test
    fun `register rejects blank password with plain-text 400`() {
        postJson("/auth/register", """{"username":"alice","password":""}""")
            .andExpect(status().isBadRequest)
            .andExpect(content().string("Password must not be blank"))
    }

    @Test
    fun `register rejects username longer than 50 characters`() {
        val longName = "a".repeat(51)
        postJson("/auth/register", """{"username":"$longName","password":"hunter2"}""")
            .andExpect(status().isBadRequest)
            .andExpect(content().string("Username must be at most 50 characters"))
    }

    @Test
    fun `register rejects password longer than 72 characters`() {
        val longPass = "x".repeat(73)
        postJson("/auth/register", """{"username":"alice","password":"$longPass"}""")
            .andExpect(status().isBadRequest)
            .andExpect(content().string("Password must be at most 72 characters"))
    }

    @Test
    fun `login rejects blank username with plain-text 400`() {
        postJson("/auth/login", """{"username":"","password":"hunter2"}""")
            .andExpect(status().isBadRequest)
            .andExpect(content().string("Username must not be blank"))
    }

    @Test
    fun `logout rejects blank session token with plain-text 400`() {
        postJson("/auth/logout", """{"sessionToken":""}""")
            .andExpect(status().isBadRequest)
            .andExpect(content().string("Session token must not be blank"))
    }
}
