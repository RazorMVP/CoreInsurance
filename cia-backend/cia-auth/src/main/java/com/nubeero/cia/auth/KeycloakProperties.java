package com.nubeero.cia.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Resource-server trust config. {@link #serverUrl} is the Keycloak base URL
 * whose realms this resource server trusts: an incoming token's {@code iss}
 * must be {@code {serverUrl}/realms/{realm}} for some non-empty realm. Backed
 * by {@code cia.keycloak.*}.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "cia.keycloak")
public class KeycloakProperties {

    /** Keycloak base URL, e.g. {@code http://localhost:8280}. Trailing slash trimmed on read. */
    private String serverUrl = "http://localhost:8280";

    /** Normalised base with any trailing slash removed. */
    public String normalisedServerUrl() {
        String s = serverUrl == null ? "" : serverUrl.trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
