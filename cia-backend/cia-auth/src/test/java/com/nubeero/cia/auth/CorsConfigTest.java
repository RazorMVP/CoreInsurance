package com.nubeero.cia.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

/**
 * Unit tests for the CORS policy built by {@link CorsConfig#corsConfiguration(CiaCorsProperties)}.
 *
 * <p>Pure (no Spring context) — exercises the produced {@link CorsConfiguration} directly:
 * exact-origin allow/deny, credentialed-request rules, the exposed download header, the method
 * set, and wildcard origin patterns (Vercel preview URLs).
 */
class CorsConfigTest {

    private static CiaCorsProperties props(List<String> origins, List<String> patterns) {
        CiaCorsProperties p = new CiaCorsProperties();
        p.setAllowedOrigins(origins);
        p.setAllowedOriginPatterns(patterns);
        return p;
    }

    @Test
    void allowsConfiguredOrigins_withCredentialsAndExposedDownloadHeader() {
        CorsConfiguration cfg = CorsConfig.corsConfiguration(
                props(List.of("http://localhost:5173", "https://app.example.com"), List.of()));

        // allowCredentials is required (SPA sends Authorization: Bearer); forbids the "*" wildcard.
        assertThat(cfg.getAllowCredentials()).isTrue();
        assertThat(cfg.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(cfg.checkOrigin("https://app.example.com")).isEqualTo("https://app.example.com");
        // Content-Disposition exposed so the blob/zip download flows can read the filename.
        assertThat(cfg.getExposedHeaders()).contains("Content-Disposition");
        assertThat(cfg.getAllowedMethods())
                .contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(cfg.getAllowedHeaders()).contains("Authorization", "Content-Type");
    }

    @Test
    void rejectsUnconfiguredOrigin() {
        CorsConfiguration cfg = CorsConfig.corsConfiguration(
                props(List.of("http://localhost:5173"), List.of()));

        assertThat(cfg.checkOrigin("https://evil.example")).isNull();
    }

    @Test
    void honoursOriginPatterns_forPreviewUrls() {
        CorsConfiguration cfg = CorsConfig.corsConfiguration(
                props(List.of(), List.of("https://*.vercel.app")));

        assertThat(cfg.checkOrigin("https://back-office-git-feat.vercel.app"))
                .isEqualTo("https://back-office-git-feat.vercel.app");
        assertThat(cfg.checkOrigin("https://evil.example")).isNull();
    }

    @Test
    void emptyConfig_deniesAllOrigins() {
        CorsConfiguration cfg = CorsConfig.corsConfiguration(props(List.of(), List.of()));

        assertThat(cfg.checkOrigin("http://localhost:5173")).isNull();
    }
}
