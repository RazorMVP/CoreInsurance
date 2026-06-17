package com.nubeero.cia.auth;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS policy for the browser SPAs (back-office + platform console) calling the internal
 * {@code /api/**} surface cross-origin (the prod SPAs are served from Vercel, a different origin
 * than the API). Wired into the filter chain by both {@link SecurityConfig} (prod) and
 * {@link DevSecurityConfig} (dev) — CORS lives in the security filter chain, so it must be
 * attached to whichever chain actually serves the request.
 *
 * <p><b>Why {@code allowCredentials(true)} + enumerated origins:</b> the SPA sends
 * {@code Authorization: Bearer}, a credentialed request. The CORS spec forbids the {@code "*"}
 * origin wildcard for credentialed requests, so origins must be listed exactly
 * ({@code cia.cors.allowed-origins}) or matched by pattern ({@code cia.cors.allowed-origin-patterns},
 * for Vercel preview URLs). {@code Content-Disposition} is exposed so the blob/zip download flows
 * (PDF receipts, bulk ZIP) can read the server-supplied filename.
 *
 * <p>The partner API ({@code /partner/**}, {@code PartnerSecurityConfig} in cia-partner-api) is
 * machine-to-machine (OAuth2 client-credentials, no browser origin) and is deliberately left
 * without CORS.
 */
@Configuration
@EnableConfigurationProperties(CiaCorsProperties.class)
public class CorsConfig {

    /**
     * Builds the single {@link CorsConfiguration} applied to every path. Package-private + static
     * so {@code CorsConfigTest} can assert the policy without a Spring context.
     */
    static CorsConfiguration corsConfiguration(CiaCorsProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.getAllowedOrigins());
        if (!props.getAllowedOriginPatterns().isEmpty()) {
            cfg.setAllowedOriginPatterns(props.getAllowedOriginPatterns());
        }
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Tenant-ID"));
        cfg.setExposedHeaders(List.of("Content-Disposition"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        return cfg;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CiaCorsProperties props) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration(props));
        return source;
    }
}
