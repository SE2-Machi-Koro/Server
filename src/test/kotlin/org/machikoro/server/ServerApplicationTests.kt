package org.machikoro.server

import io.github.cdimascio.dotenv.Dotenv
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun bootstrapAndRunShouldLoadDotenvApplyPropertiesAndInvokeRunner() {
        val key = "BOOTSTRAP_ENV_KEY"
        val value = "bootstrap-value"
        var runnerCalled = false

        try {
            bootstrapAndRun(
                args = arrayOf("--test"),
                dotenvLoader = { createDotenvFromContent("$key=$value\n") },
                runner = {
                    runnerCalled = true
                    assertEquals("--test", it.single())
                }
            )

            assertTrue(runnerCalled)
            assertEquals(value, System.getProperty(key))
        } finally {
            System.clearProperty(key)
        }
    }

    @Test
    fun mainShouldUseInjectedMainHooks() {
        val key = "MAIN_HOOK_ENV_KEY"
        val value = "main-hook-value"
        val previousLoader = dotenvLoaderForMain
        val previousRunner = appRunnerForMain
        var runnerCalled = false

        try {
            dotenvLoaderForMain = { createDotenvFromContent("$key=$value\n") }
            appRunnerForMain = {
                runnerCalled = true
                assertEquals("--from-main", it.single())
            }

            main(arrayOf("--from-main"))

            assertTrue(runnerCalled)
            assertEquals(value, System.getProperty(key))
        } finally {
            dotenvLoaderForMain = previousLoader
            appRunnerForMain = previousRunner
            System.clearProperty(key)
        }
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
