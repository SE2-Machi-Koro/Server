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
     * Permits unauthenticated access to WebSocket endpoints, static resources, and the root/index page.
     * Requires authentication for API endpoints.
     * Note: CSRF protection is enabled by default.
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/", "/index.html", "/css/**", "/js/**", "/webjars/**").permitAll()
                auth.requestMatchers("/ws", "/ws/**").permitAll()
                auth.requestMatchers("/api/**").authenticated()
                auth.anyRequest().authenticated()
            }

        return http.build()
    }
}
