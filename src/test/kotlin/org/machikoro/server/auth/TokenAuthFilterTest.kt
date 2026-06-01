package org.machikoro.server.auth

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.models.UserModel
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

class TokenAuthFilterTest {

    private val userDao = mock<UserDao>()
    private val filter = TokenAuthFilter(userDao)
    private val chain = mock<FilterChain>()

    @BeforeEach
    fun setUp() {
        // Isolate each test from leftover SecurityContext state
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── Happy paths ──────────────────────────────────────────────────────────

    @Test
    fun `valid token for regular user sets ROLE_USER authority`() {
        val token = "valid-token"
        whenever(userDao.findBySessionToken(token)).thenReturn(regularUser(sessionToken = token))

        doFilter(withBearer(token))

        val auth = SecurityContextHolder.getContext().authentication!!
        assertTrue(auth.authorities.contains(SimpleGrantedAuthority("ROLE_USER")))
    }

    @Test
    fun `valid token for admin user sets both ROLE_USER and ROLE_ADMIN`() {
        val token = "admin-token"
        whenever(userDao.findBySessionToken(token)).thenReturn(adminUser(sessionToken = token))

        doFilter(withBearer(token))

        val auth = SecurityContextHolder.getContext().authentication!!
        assertTrue(auth.authorities.contains(SimpleGrantedAuthority("ROLE_USER")))
        assertTrue(auth.authorities.contains(SimpleGrantedAuthority("ROLE_ADMIN")))
    }

    @Test
    fun `valid token sets UserPrincipal with correct id and username`() {
        val token = "valid-token"
        whenever(userDao.findBySessionToken(token)).thenReturn(
            regularUser(id = 42, username = "alice", sessionToken = token)
        )

        doFilter(withBearer(token))

        val userPrincipal = SecurityContextHolder.getContext().authentication!!.principal as? UserPrincipal
        assertNotNull(userPrincipal)
        assertEquals(42, userPrincipal!!.userId)
        assertEquals("alice", userPrincipal.username)
    }

    @Test
    fun `valid token produces a fully authenticated UsernamePasswordAuthenticationToken`() {
        val token = "valid-token"
        whenever(userDao.findBySessionToken(token)).thenReturn(regularUser(sessionToken = token))

        doFilter(withBearer(token))

        val auth = SecurityContextHolder.getContext().authentication!!
        assertTrue(auth is UsernamePasswordAuthenticationToken)
        assertTrue(auth.isAuthenticated)
    }

    @Test
    fun `regular user does not receive ROLE_ADMIN`() {
        val token = "valid-token"
        whenever(userDao.findBySessionToken(token)).thenReturn(regularUser(sessionToken = token))

        doFilter(withBearer(token))

        val auth = SecurityContextHolder.getContext().authentication!!
        assertTrue(auth.authorities.none { it == SimpleGrantedAuthority("ROLE_ADMIN") })
    }

    // ── Chain always proceeds ────────────────────────────────────────────────

    @Test
    fun `chain proceeds when token is valid`() {
        val token = "valid-token"
        whenever(userDao.findBySessionToken(token)).thenReturn(regularUser(sessionToken = token))
        val request = withBearer(token)
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
    }

    @Test
    fun `chain proceeds when Authorization header is absent`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
    }

    @Test
    fun `chain proceeds when token is not found in database`() {
        whenever(userDao.findBySessionToken(any())).thenReturn(null)
        val request = withBearer("ghost-token")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
    }

    // ── No-auth pass-throughs ────────────────────────────────────────────────

    @Test
    fun `absent Authorization header leaves SecurityContext empty and skips DAO`() {
        doFilter(MockHttpServletRequest())

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(userDao, never()).findBySessionToken(any())
    }

    @Test
    fun `Authorization header without Bearer prefix leaves SecurityContext empty and skips DAO`() {
        doFilter(withHeader("Authorization", "Basic dXNlcjpwYXNz"))

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(userDao, never()).findBySessionToken(any())
    }

    @Test
    fun `bare token without Bearer prefix leaves SecurityContext empty and skips DAO`() {
        doFilter(withHeader("Authorization", "raw-token-no-prefix"))

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(userDao, never()).findBySessionToken(any())
    }

    @Test
    fun `Bearer prefix with blank token leaves SecurityContext empty and skips DAO`() {
        doFilter(withHeader("Authorization", "Bearer "))

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(userDao, never()).findBySessionToken(any())
    }

    @Test
    fun `Bearer prefix with whitespace-only token leaves SecurityContext empty and skips DAO`() {
        doFilter(withHeader("Authorization", "Bearer    "))

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(userDao, never()).findBySessionToken(any())
    }

    @Test
    fun `valid token with unknown user leaves SecurityContext empty`() {
        whenever(userDao.findBySessionToken("ghost-token")).thenReturn(null)

        doFilter(withBearer("ghost-token"))

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    // ── Idempotency — do not overwrite existing authentication ───────────────

    @Test
    fun `pre-populated SecurityContext is not overwritten and DAO is not queried`() {
        val existing = UsernamePasswordAuthenticationToken(
            UserPrincipal(99, "pre-auth"),
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
        SecurityContextHolder.getContext().authentication = existing

        doFilter(withBearer("some-token"))

        assertEquals(existing, SecurityContextHolder.getContext().authentication)
        verify(userDao, never()).findBySessionToken(any())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun doFilter(request: MockHttpServletRequest) {
        filter.doFilter(request, MockHttpServletResponse(), chain)
    }

    private fun withBearer(token: String) = withHeader("Authorization", "Bearer $token")

    private fun withHeader(name: String, value: String) =
        MockHttpServletRequest().also { it.addHeader(name, value) }

    private fun regularUser(
        id: Int = 1,
        username: String = "alice",
        sessionToken: String? = null,
    ) = UserModel(
        id = id,
        username = username,
        passwordHash = null,
        sessionToken = sessionToken,
        totalWins = 0,
        totalGamesPlayed = 0,
        isAdmin = false,
    )

    private fun adminUser(
        id: Int = 2,
        username: String = "admin",
        sessionToken: String? = null,
    ) = UserModel(
        id = id,
        username = username,
        passwordHash = null,
        sessionToken = sessionToken,
        totalWins = 0,
        totalGamesPlayed = 0,
        isAdmin = true,
    )
}