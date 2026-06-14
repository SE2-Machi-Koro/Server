package org.machikoro.server.controller

import org.machikoro.server.auth.UserPrincipal
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithSecurityContext
import org.springframework.security.test.context.support.WithSecurityContextFactory

// Injects a UserPrincipal into SecurityContextHolder for @WebMvcTest without filters
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@WithSecurityContext(factory = WithUserPrincipalFactory::class)
annotation class WithUserPrincipal(val userId: Int = 1, val username: String = "testuser")

class WithUserPrincipalFactory : WithSecurityContextFactory<WithUserPrincipal> {
    override fun createSecurityContext(annotation: WithUserPrincipal) =
        SecurityContextHolder.createEmptyContext().apply {
            authentication = UsernamePasswordAuthenticationToken(
                UserPrincipal(userId = annotation.userId, username = annotation.username),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        }
}
