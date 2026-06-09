package org.machikoro.server.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DockerHealthcheckConfigTest {

    private val yamlMapper = YAMLMapper()
    private val projectRoot: Path = Path.of("").toAbsolutePath()

    @Test
    fun `backend compose service exposes actuator health through docker healthcheck`() {
        val backend = composeService("backend")
        val healthcheck = backend.path("healthcheck")
        val testCommand = healthcheck.path("test").map(JsonNode::asText)

        assertEquals(listOf("CMD-SHELL", "curl -fsS http://localhost:8080/actuator/health || exit 1"), testCommand)
        assertEquals("15s", healthcheck.path("interval").asText())
        assertEquals("5s", healthcheck.path("timeout").asText())
        assertEquals(5, healthcheck.path("retries").asInt())
        assertEquals("60s", healthcheck.path("start_period").asText())
    }

    @Test
    fun `backend docker image installs curl required by compose healthcheck`() {
        val dockerfile = Files.readString(projectRoot.resolve("Dockerfile"))

        assertTrue(dockerfile.contains("FROM eclipse-temurin:21.0.6_7-jre-jammy AS runtime-base"))
        assertTrue(dockerfile.contains("apt-get install -y --no-install-recommends curl"))
    }

    private fun composeService(name: String): JsonNode {
        val compose = yamlMapper.readTree(projectRoot.resolve("compose.yaml").toFile())
        return compose.path("services").path(name)
    }
}
