package org.machikoro.server.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DockerHealthcheckConfigTest {

    private val yamlMapper = YAMLMapper()
    private val projectRoot: Path = Path.of("").toAbsolutePath()

    @Test
    fun `backend compose service exposes actuator health through docker healthcheck`() {
        val backend = composeService("compose.yaml", "backend")
        val healthcheck = backend.path("healthcheck")

        assertEquals(listOf("CMD-SHELL", "curl -fsS http://localhost:8080/actuator/health || exit 1"), healthcheck.testCommand())
        assertEquals("15s", healthcheck.path("interval").asText())
        assertEquals("5s", healthcheck.path("timeout").asText())
        assertEquals(5, healthcheck.path("retries").asInt())
        assertEquals("60s", healthcheck.path("start_period").asText())
    }

    @Test
    fun `postgres compose service exposes pg_isready healthcheck`() {
        val postgres = composeService("compose.yaml", "postgres")
        val healthcheck = postgres.path("healthcheck")

        assertEquals(listOf("CMD-SHELL", "pg_isready -U ${'$'}{DB_USERNAME} -d ${'$'}{DB_NAME:-machikoro}"), healthcheck.testCommand())
        assertEquals("5s", healthcheck.path("interval").asText())
        assertEquals("5s", healthcheck.path("timeout").asText())
        assertEquals(5, healthcheck.path("retries").asInt())
        assertEquals("30s", healthcheck.path("start_period").asText())
    }

    @Test
    fun `backend waits for postgres to be healthy before starting`() {
        val backend = composeService("compose.yaml", "backend")

        assertEquals("service_healthy", backend.path("depends_on").path("postgres").path("condition").asText())
    }

    @Test
    fun `backend production compose uses internal postgres connection settings`() {
        val environment = composeService("compose.yaml", "backend").environment()

        assertTrue(environment.contains("DB_HOST=postgres"))
        assertTrue(environment.contains("DB_PORT=5432"))
        assertTrue(environment.contains("SERVER_PORT=8080"))
        assertTrue(environment.contains("SPRING_DOCKER_COMPOSE_ENABLED=false"))
    }

    @Test
    fun `postgres production compose uses required database environment settings`() {
        val environment = composeService("compose.yaml", "postgres").environment()

        assertTrue(environment.contains("POSTGRES_DB=${'$'}{DB_NAME:-machikoro}"))
        assertTrue(environment.contains("POSTGRES_PASSWORD=${'$'}{DB_PASSWORD}"))
        assertTrue(environment.contains("POSTGRES_USER=${'$'}{DB_USERNAME}"))
    }

    @Test
    fun `production compose keeps postgres private while dev compose exposes it locally`() {
        val productionPostgres = composeService("compose.yaml", "postgres")
        val devPostgres = composeService("compose-dev.yaml", "postgres")

        assertFalse(productionPostgres.has("ports"))
        assertEquals(listOf("${'$'}{DB_PORT:-5432}:5432"), devPostgres.path("ports").map(JsonNode::asText))
    }

    @Test
    fun `compose files preserve postgres data through named volumes`() {
        listOf("compose.yaml", "compose-dev.yaml").forEach { composeFile ->
            val compose = compose(composeFile)

            assertEquals(listOf("postgres_data:/var/lib/postgresql"), compose.path("services").path("postgres").path("volumes").map(JsonNode::asText))
            assertTrue(compose.path("volumes").has("postgres_data"))
        }
    }

    @Test
    fun `development pgadmin depends on postgres and uses persistent storage`() {
        val pgAdmin = composeService("compose-dev.yaml", "pgAdmin")

        assertEquals(listOf("postgres"), pgAdmin.path("depends_on").map(JsonNode::asText))
        assertEquals(listOf("pgadmin_data:/var/lib/pgadmin"), pgAdmin.path("volumes").map(JsonNode::asText))
        assertEquals(listOf("5050:80"), pgAdmin.path("ports").map(JsonNode::asText))
        assertTrue(compose("compose-dev.yaml").path("volumes").has("pgadmin_data"))
    }

    @Test
    fun `backend docker image installs curl required by compose healthcheck`() {
        val dockerfile = Files.readString(projectRoot.resolve("Dockerfile"))

        assertTrue(dockerfile.contains("FROM eclipse-temurin:21.0.6_7-jre-jammy AS runtime-base"))
        assertTrue(dockerfile.contains("apt-get install -y --no-install-recommends curl"))
    }

    private fun composeService(composeFile: String, name: String): JsonNode =
        compose(composeFile).path("services").path(name)

    private fun compose(composeFile: String): JsonNode =
        yamlMapper.readTree(projectRoot.resolve(composeFile).toFile())

    private fun JsonNode.testCommand(): List<String> =
        path("test").map(JsonNode::asText)

    private fun JsonNode.environment(): List<String> =
        path("environment").map(JsonNode::asText)
}
