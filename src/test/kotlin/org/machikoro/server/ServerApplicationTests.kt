package org.machikoro.server

import io.github.cdimascio.dotenv.Dotenv
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Files

@SpringBootTest
class ServerApplicationTests {

    @Test
    fun contextLoads() {
    }

    @Test
    fun loadDotenvShouldNotThrowWhenEnvFileIsMissing() {
        assertDoesNotThrow { loadDotenv() }
    }

    @Test
    fun applyDotenvToSystemPropertiesShouldPopulateAllEntries() {
        val key = "TEST_ENV_KEY"
        val value = "test-value"
        val dotenv = createDotenvFromContent("$key=$value\n")

        try {
            applyDotenvToSystemProperties(dotenv)
            assertEquals(value, System.getProperty(key))
        } finally {
            System.clearProperty(key)
        }
    }

    @Test
    fun applyDotenvToSystemPropertiesShouldHandleEmptyEntries() {
        val dotenv = createDotenvFromContent("\n")
        assertDoesNotThrow { applyDotenvToSystemProperties(dotenv) }
        assertFalse(System.getProperties().containsKey(""))
    }

    private fun createDotenvFromContent(content: String): Dotenv {
        val tempDir = Files.createTempDirectory("dotenv-test")
        Files.writeString(tempDir.resolve(".env"), content)

        return Dotenv.configure()
            .directory(tempDir.toString())
            .filename(".env")
            .ignoreIfMalformed()
            .load()
    }

}
