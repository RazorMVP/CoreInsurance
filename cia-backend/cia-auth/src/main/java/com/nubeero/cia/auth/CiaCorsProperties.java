package com.nubeero.cia.auth;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS allow-list for the internal API ({@code /api/**}) — the origins the browser SPAs
 * (back-office + platform console) are permitted to call cross-origin.
 *
 * <p>Bound from {@code cia.cors.*} in application.yml; both lists accept a comma-separated
 * env value (Spring relaxed binding). See {@link CorsConfig} for how these feed the
 * {@code CorsConfigurationSource}. The partner API ({@code /partner/**}) is machine-to-machine
 * and intentionally NOT CORS-enabled.
 */
@Getter
@Setter
@ConfigurationProperties("cia.cors")
public class CiaCorsProperties {

    /** Exact-match browser origins (e.g. {@code https://app.cia.example}). CSV-bindable. */
    private List<String> allowedOrigins = new ArrayList<>();

    /**
     * Wildcard origin patterns (e.g. {@code https://*.vercel.app}) for preview deploys whose
     * subdomain varies per branch. Matched via {@code CorsConfiguration.checkOrigin}.
     */
    private List<String> allowedOriginPatterns = new ArrayList<>();
}
