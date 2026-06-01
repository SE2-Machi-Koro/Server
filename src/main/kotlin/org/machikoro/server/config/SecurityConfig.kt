package org.machikoro.server.config

import org.machikoro.server.auth.TokenAuthFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    // Mirror the same default as DebugController so security rules match controller availability
    @Value("\${debug.enabled:false}") private val debugEnabled: Boolean,
    private val tokenAuthFilter: TokenAuthFilter,
) {

    /**
     * Configure security filter chain.
     * Permits unauthenticated access to WebSocket endpoints, static resources, the root/index page,
     * and the `/actuator/health` endpoint so Docker, CI, and monitoring can poll it without credentials.
     * Requires authentication for API endpoints.
     * CSRF is disabled for WebSocket endpoints to allow SockJS fallback HTTP POST requests
     * and for the public auth endpoints since clients (e.g. the Android app) post JSON
     * without a session-bound CSRF token.
     * CSRF remains enabled for all other endpoints including API routes.
     * Debug endpoints are only opened when debug.enabled=true (set via DEBUG_ENABLED env var).
     * TokenAuthFilter validates Bearer session tokens for all authenticated REST endpoints.
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .addFilterBefore(tokenAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .csrf { csrf ->
                // Exempt token-authenticated REST endpoints — Android client never sends CSRF tokens
                val csrfExempt = mutableListOf("/ws", "/ws/**", "/ws-sockjs", "/ws-sockjs/**", "/auth/**", "/leaderboard")
                if (debugEnabled) csrfExempt.add("/debug/**")
                csrf.ignoringRequestMatchers(*csrfExempt.toTypedArray())
            }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/", "/index.html", "/css/**", "/js/**", "/webjars/**").permitAll()
                auth.requestMatchers("/ws", "/ws/**", "/ws-sockjs", "/ws-sockjs/**").permitAll()
                auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                auth.requestMatchers(
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/api-docs",
                    "/api-docs/**",
                    "/v3/api-docs",
                    "/v3/api-docs/**",
                    "/springwolf/**"
                ).permitAll()
                auth.requestMatchers("/auth/**").permitAll()
                // Only open /debug/** when debug endpoints are enabled
                if (debugEnabled) auth.requestMatchers("/debug/**").permitAll()
                auth.requestMatchers("/api/**").authenticated()
                auth.anyRequest().authenticated()
            }

        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}