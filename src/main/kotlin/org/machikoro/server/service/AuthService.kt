package org.machikoro.server.service

import org.machikoro.server.dao.UserDao
import org.machikoro.server.dto.RegisterResponse
import org.machikoro.server.exception.DuplicateUserException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val userDao: UserDao,
    private val passwordEncoder: PasswordEncoder,
) {

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
}
