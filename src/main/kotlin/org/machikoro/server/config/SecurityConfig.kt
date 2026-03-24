package org.machikoro.server.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {

    /**
     * Configure security filter chain.
     * Permits unauthenticated access to WebSocket endpoints and disables CSRF for testing.
     * Note: For production, implement proper CSRF protection and authentication.
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/ws/**").permitAll()
                auth.requestMatchers("/api/**").permitAll()
                auth.anyRequest().permitAll()
            }

        return http.build()
    }
}

