package org.machikoro.server.config

import org.junit.jupiter.api.Test
import org.machikoro.server.SpringBootTestWithoutDataSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTestWithoutDataSource
@AutoConfigureMockMvc
@TestPropertySource(properties = [
    "management.health.db.enabled=false",
    "spring.flyway.enabled=false"
])
class HealthEndpointTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun healthEndpointIsAccessibleWithoutAuthentication() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
    }

    @Test
    fun healthEndpointReportsStatusUp() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }
}
