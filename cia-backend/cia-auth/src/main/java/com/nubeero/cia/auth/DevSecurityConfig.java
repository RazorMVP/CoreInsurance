package com.nubeero.cia.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
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
 * <p><b>E2E:</b> {@code @EnableMethodSecurity} (on {@link SecurityConfig}) is
 * profile-unconditional, so {@code @PreAuthorize} still rejects requests with no
 * principal even under this {@code permitAll} chain. When the {@code e2e} profile
 * is <em>also</em> active (run as {@code dev,e2e}), an {@link E2eMockAuthFilter}
 * is added that injects a full-authority principal so the Playwright golden paths
 * can hit protected endpoints without a live Keycloak. It is added only for
 * {@code e2e} — plain {@code dev} is unchanged.
 *
 * NEVER active in production (profile guard ensures this).
 */
@Configuration
@Profile("dev")
@Order(1)
public class DevSecurityConfig {

    @Bean
    public SecurityFilterChain devSecurityFilterChain(
            HttpSecurity http, CorsConfigurationSource corsConfigurationSource,
            Environment environment) throws Exception {
        http
                .securityMatcher("/**")
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        if (environment.acceptsProfiles(Profiles.of("e2e"))) {
            http.addFilterBefore(new E2eMockAuthFilter(), UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }
}
