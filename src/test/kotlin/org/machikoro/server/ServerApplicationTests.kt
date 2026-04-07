package org.machikoro.server

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ServerApplicationTests {

    private val originalUsername = System.getProperty("DB_USERNAME")
    private val originalPassword = System.getProperty("DB_PASSWORD")

    @AfterEach
    fun restoreProperties() {
        if (originalUsername != null) {
            System.setProperty("DB_USERNAME", originalUsername)
        } else {
            System.clearProperty("DB_USERNAME")
        }

        if (originalPassword != null) {
            System.setProperty("DB_PASSWORD", originalPassword)
        } else {
            System.clearProperty("DB_PASSWORD")
        }
    }

    @Test
    fun connectToDatabaseThrowsWhenUsernameIsMissing() {
        System.clearProperty("DB_USERNAME")

        assertThrows(IllegalArgumentException::class.java) {
            connectToDatabase()
        }
    }

    @Test
    fun connectToDatabaseThrowsWhenPasswordIsMissing() {
        System.setProperty("DB_USERNAME", "test")
        System.clearProperty("DB_PASSWORD")

        assertThrows(IllegalArgumentException::class.java) {
            connectToDatabase()
        }
    }
}