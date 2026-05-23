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
        log.info("Initializing Keycloak admin client → server={}, adminRealm={}, targetRealm={}",
                props.getServerUrl(), props.getAdminRealm(), props.getTargetRealm());
        return KeycloakBuilder.builder()
                .serverUrl(props.getServerUrl())
                .realm(props.getAdminRealm())
                .grantType(org.keycloak.OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(props.getClientId())
                .clientSecret(props.getClientSecret())
                .build();
    }
}
