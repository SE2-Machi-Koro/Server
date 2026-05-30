package org.machikoro.server.database

import org.machikoro.server.dao.UserDao
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

// Seeds 4 admin accounts at startup — only runs when debug.enabled=true
@Component
@Order(3)
@ConditionalOnProperty(name = ["debug.enabled"], havingValue = "true")
class AdminSeeder(
    private val userDao: UserDao,
    private val passwordEncoder: PasswordEncoder,
    // Falls back to blank so missing env var logs a warning instead of crashing
    @Value("\${admin.password:}") private val adminPassword: String,
) : CommandLineRunner {

    private val logger = LoggerFactory.getLogger(AdminSeeder::class.java)

    companion object {
        val ADMIN_USERNAMES = listOf("admin_1", "admin_2", "admin_3", "admin_4")
    }

    override fun run(vararg args: String) {
        if (adminPassword.isBlank()) {
            logger.warn("ADMIN_PASSWORD not set — skipping admin account seeding")
            return
        }

        ADMIN_USERNAMES.forEach { username ->
            val existing = userDao.findByUsername(username)
            when {
                existing == null -> {
                    val hash = passwordEncoder.encode(adminPassword)
                    userDao.create(username, hash, isAdmin = true)
                    logger.info("Created admin user '{}'", username)
                }
                !existing.isAdmin -> {
                    // Upgrade if somehow the account exists without the flag
                    userDao.setAdmin(existing.id, true)
                    logger.info("Upgraded '{}' to admin", username)
                }
                else -> logger.debug("Admin user '{}' already exists", username)
            }
        }
    }
}