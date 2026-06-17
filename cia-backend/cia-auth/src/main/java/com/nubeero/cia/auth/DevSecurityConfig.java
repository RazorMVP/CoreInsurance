package com.nubeero.cia.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Dev-only security override — permits all requests without JWT validation.
 * When no JWT is present, TenantIdentifierResolver defaults to the "public" schema,
 * where Flyway migrations (including V17/V18 reports tables) have been applied.
 *
 * <p>CORS is still applied (same {@link CorsConfigurationSource} as the prod chain) so a
 * developer running the SPA against this backend without the Vite proxy gets correct
 * cross-origin behaviour, and so preflight handling matches production.
 *
 * NEVER active in production (profile guard ensures this).
 */
@Configuration
@Profile("dev")
@Order(1)
public class DevSecurityConfig {

    @Bean
    public SecurityFilterChain devSecurityFilterChain(
            HttpSecurity http, CorsConfigurationSource corsConfigurationSource) throws Exception {
        return http
                .securityMatcher("/**")
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
