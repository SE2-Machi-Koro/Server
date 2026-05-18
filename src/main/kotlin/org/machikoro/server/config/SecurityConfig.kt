package org.machikoro.server.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {

    /**
     * Configure security filter chain.
     * Permits unauthenticated access to WebSocket endpoints, static resources, the root/index page,
     * and the `/actuator/health` endpoint so Docker, CI, and monitoring can poll it without credentials.
     * Requires authentication for API endpoints.
     * CSRF is disabled for WebSocket endpoints to allow SockJS fallback HTTP POST requests
     * and for the public auth endpoints since clients (e.g. the Android app) post JSON
     * without a session-bound CSRF token.
     * CSRF remains enabled for all other endpoints including API routes.
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { csrf ->
                csrf.ignoringRequestMatchers(
                    "/ws", "/ws/**", "/ws-sockjs", "/ws-sockjs/**",
                    "/auth/**",
                    "/debug/**",
                )
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
                // Debug endpoint is open so frontend can call it without a session
                auth.requestMatchers("/debug/**").permitAll()
                auth.requestMatchers("/api/**").authenticated()
                auth.anyRequest().authenticated()
            }

        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
