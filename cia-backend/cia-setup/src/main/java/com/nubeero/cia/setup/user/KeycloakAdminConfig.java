package com.nubeero.cia.setup.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keycloak admin client wiring. Gated on {@code cia.keycloak.admin.enabled}
 * so dev environments without a reachable Keycloak instance don't fail to
 * boot — {@link UserController} returns 503 when the bean is absent.
 *
 * <p>Authentication uses OAuth2 Client Credentials against the configured
 * admin realm (almost always {@code master}). The service-account client
 * must have the {@code realm-management} composite role on the target
 * realm — without it every user operation throws 403.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cia.keycloak.admin", name = "enabled", havingValue = "true")
public class KeycloakAdminConfig {

    private final KeycloakAdminProperties props;

    @Bean(destroyMethod = "close")
    public Keycloak keycloakAdmin() {
        KeycloakBuilder b = KeycloakBuilder.builder()
                .serverUrl(props.getServerUrl())
                .realm(props.getAdminRealm())
                .clientId(props.getClientId());

        // Decision matrix: clientSecret present → client-credentials (prod);
        // username/password present → password grant (dev, against admin-cli).
        // Exactly one of the two MUST be configured at startup.
        if (props.getClientSecret() != null && !props.getClientSecret().isBlank()) {
            log.info("Initializing Keycloak admin client (client-credentials) → server={}, adminRealm={}, targetRealm={}, clientId={}",
                    props.getServerUrl(), props.getAdminRealm(), props.getTargetRealm(), props.getClientId());
            b.grantType(org.keycloak.OAuth2Constants.CLIENT_CREDENTIALS)
             .clientSecret(props.getClientSecret());
        } else if (props.getUsername() != null && !props.getUsername().isBlank()) {
            log.info("Initializing Keycloak admin client (password grant) → server={}, adminRealm={}, targetRealm={}, clientId={}, username={}",
                    props.getServerUrl(), props.getAdminRealm(), props.getTargetRealm(), props.getClientId(), props.getUsername());
            b.grantType(org.keycloak.OAuth2Constants.PASSWORD)
             .username(props.getUsername())
             .password(props.getPassword());
        } else {
            throw new IllegalStateException(
                    "cia.keycloak.admin.enabled=true but neither client-secret nor username/password is configured. " +
                    "Set KEYCLOAK_ADMIN_CLIENT_SECRET (prod) or KEYCLOAK_ADMIN_USERNAME/KEYCLOAK_ADMIN_PASSWORD (dev).");
        }

        return b.build();
    }
}
