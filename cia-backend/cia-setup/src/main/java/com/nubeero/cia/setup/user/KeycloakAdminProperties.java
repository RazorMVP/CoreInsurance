package com.nubeero.cia.setup.user;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Admin-client config for the Keycloak proxy. Backed by
 * {@code cia.keycloak.admin.*} properties in {@code application.yml}.
 *
 * <p>When {@code enabled = false} the {@code Keycloak} bean is not created
 * and {@link UserController} short-circuits to HTTP 503 — useful in dev when
 * no Keycloak instance is reachable.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "cia.keycloak.admin")
public class KeycloakAdminProperties {

    /** Master switch — disable in dev when no Keycloak is running. */
    private boolean enabled = false;

    /** Keycloak server base URL, e.g. {@code http://localhost:8280}. */
    private String serverUrl;

    /**
     * Realm against which the admin client authenticates. Almost always
     * {@code master} — that's where service accounts with realm-management
     * roles live.
     */
    private String adminRealm = "master";

    /** Service account client id with {@code realm-management} composite role. */
    private String clientId;

    /** Service account client secret. */
    private String clientSecret;

    /**
     * Realm whose users are managed by this admin client. Today mirrors
     * {@code spring.security.oauth2.resourceserver.jwt} realm (single shared
     * realm). When realm-per-tenant lands, this becomes the per-request
     * default and TenantContext drives the real lookup.
     */
    private String targetRealm;
}
