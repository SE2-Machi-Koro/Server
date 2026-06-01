package org.machikoro.server.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.machikoro.server.dao.UserDao
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

// Validates Bearer tokens for HTTP REST endpoints (WebSocket uses StompAuthChannelInterceptor)
@Component
class TokenAuthFilter(private val userDao: UserDao) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val token = request.getHeader("Authorization")
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?.takeIf { it.isNotBlank() }

        // Only authenticate if token present and context not already populated
        if (token != null && SecurityContextHolder.getContext().authentication == null) {
            val user = userDao.findBySessionToken(token)
            if (user != null) {
                val authorities = buildList {
                    add(SimpleGrantedAuthority("ROLE_USER"))
                    if (user.isAdmin) add(SimpleGrantedAuthority("ROLE_ADMIN"))
                }
                val auth = UsernamePasswordAuthenticationToken(
                    UserPrincipal(user.id, user.username),
                    null,
                    authorities,
                )
                auth.details = WebAuthenticationDetailsSource().buildDetails(request)
                SecurityContextHolder.getContext().authentication = auth
            }
        }

        chain.doFilter(request, response)
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }
}