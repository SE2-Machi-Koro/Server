package org.machikoro.server.service

import org.machikoro.server.dao.UserDao
import org.machikoro.server.dto.LoginResponse
import org.machikoro.server.dto.RegisterResponse
import org.machikoro.server.exception.DuplicateUserException
import org.machikoro.server.exception.InvalidCredentialsException
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AuthService(
    private val userDao: UserDao,
    private val passwordEncoder: PasswordEncoder,
) {
    private val logger = LoggerFactory.getLogger(AuthService::class.java)

    /**
     * Validates input, hashes the password via BCrypt, and persists a new user.
     * The findByUsername + create pair runs in one transaction so a duplicate
     * inserted between the two calls will roll back rather than leak through.
     *
     * Username is trimmed and lower-cased before persistence so "Alice", "alice",
     * and " alice " all map to the same account. Password is left untouched so
     * surrounding whitespace stays significant to the caller.
     */
    @Transactional
    fun register(username: String, rawPassword: String): RegisterResponse {
        val cleanUsername = username.trim().lowercase()

        require(cleanUsername.isNotBlank()) { "Username must not be blank" }
        require(cleanUsername.length <= 50) { "Username must be at most 50 characters" }
        require(rawPassword.isNotBlank()) { "Password must not be blank" }
        // BCrypt silently truncates inputs longer than 72 bytes; reject up front
        // so clients see a clear error rather than a confusing "your password
        // worked but only the first 72 bytes" surprise later.
        require(rawPassword.length <= 72) { "Password must be at most 72 characters" }

        if (userDao.findByUsername(cleanUsername) != null) {
            throw DuplicateUserException("Username '$cleanUsername' is already taken")
        }

        val hash = passwordEncoder.encode(rawPassword)
        val id = userDao.create(cleanUsername, hash)
        return RegisterResponse(id = id, username = cleanUsername)
    }

    /**
     * Authenticates a user against the stored BCrypt hash and issues a fresh
     * session token. Unknown users, wrong passwords, and users without a stored
     * hash all collapse into the same generic InvalidCredentialsException so a
     * caller cannot distinguish "user does not exist" from "wrong password".
     */
    @Transactional
    fun login(username: String, rawPassword: String): LoginResponse {
        require(username.isNotBlank()) { "Username must not be blank" }
        require(rawPassword.isNotBlank()) { "Password must not be blank" }

        val cleanUsername = username.trim().lowercase()
        val user = userDao.findByUsername(cleanUsername)

        // Always run the BCrypt comparison — even when the user is unknown or
        // has no stored hash — so an attacker cannot enumerate valid usernames
        // by measuring response time. BCrypt is intentionally slow (~100ms),
        // so skipping the comparison on the user-not-found path would leak
        // existence through latency. See DUMMY_BCRYPT_HASH below.
        val hashToCompare = user?.passwordHash ?: DUMMY_BCRYPT_HASH
        val passwordOk = passwordEncoder.matches(rawPassword, hashToCompare)
        if (user == null || user.passwordHash == null || !passwordOk) {
            throw InvalidCredentialsException("Invalid username or password")
        }

        val token = UUID.randomUUID().toString()
        userDao.updateSessionToken(user.id, token)
        return LoginResponse(
            sessionToken = token,
            username = user.username,
            userId = user.id, // NEU
        )
    }

    /**
     * Invalidates the given session token. Idempotent: an unknown token is a
     * silent no-op so a caller cannot enumerate valid tokens by status code.
     */
    @Transactional
    fun logout(sessionToken: String) {
        require(sessionToken.isNotBlank()) { "Session token must not be blank" }
        val user = userDao.findBySessionToken(sessionToken) ?: return
        userDao.updateSessionToken(user.id, null)
        logger.info("Cleared session for user '{}'", user.username)
    }

    companion object {
        // Pre-computed BCrypt hash used as a comparison target when the
        // looked-up user does not exist (or has no stored hash). The
        // plaintext is intentionally something no real user would have.
        // What matters is that the hash is well-formed BCrypt at the same
        // cost factor as production hashes (10) so PasswordEncoder.matches
        // takes the same ~100ms whether or not the user exists.
        private const val DUMMY_BCRYPT_HASH =
            "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
    }
}
