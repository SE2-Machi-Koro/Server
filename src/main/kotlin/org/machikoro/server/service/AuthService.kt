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
     */
    @Transactional
    fun register(username: String, rawPassword: String): RegisterResponse {
        require(username.isNotBlank()) { "Username must not be blank" }
        require(rawPassword.isNotBlank()) { "Password must not be blank" }
        require(username.length <= 50) { "Username must be at most 50 characters" }

        if (userDao.findByUsername(username) != null) {
            throw DuplicateUserException("Username '$username' is already taken")
        }

        val hash = passwordEncoder.encode(rawPassword)
        val id = userDao.create(username, hash)
        return RegisterResponse(id = id, username = username)
    }
}
