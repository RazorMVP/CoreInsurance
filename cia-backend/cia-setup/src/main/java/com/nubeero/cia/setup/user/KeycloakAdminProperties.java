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

    /**
     * Client id used by the admin client. Two modes:
     * <ul>
     *   <li><b>Client-credentials (prod):</b> a confidential service-account
     *       client with the {@code realm-management} composite role. Set
     *       {@link #clientSecret} too.</li>
     *   <li><b>Password (dev):</b> Keycloak's built-in public {@code admin-cli}
     *       client. Set {@link #username} + {@link #password} to the master
     *       realm admin (defaults match docker-compose's
     *       KEYCLOAK_ADMIN/KEYCLOAK_ADMIN_PASSWORD = admin/admin).</li>
     * </ul>
     */
    private String clientId = "admin-cli";

    /** Service-account client secret (client-credentials grant). */
    private String clientSecret;

    /** Admin username (password grant — dev). */
    private String username;

    /** Admin password (password grant — dev). */
    private String password;

    /**
     * Realm whose users are managed by this admin client. Today mirrors
     * {@code spring.security.oauth2.resourceserver.jwt} realm (single shared
     * realm). When realm-per-tenant lands, this becomes the per-request
     * default and TenantContext drives the real lookup.
     */
    private String targetRealm;
}
