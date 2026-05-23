package com.nubeero.cia.setup.keycloak;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs {@link KeycloakTenantProvisioner#provisionTargetRealm()} on every
 * application start. Mirrors Flyway's "manage schema invariants at boot"
 * pattern — ops doesn't have to remember to run a separate provisioning
 * step before every deploy.
 *
 * <p>Conditional on {@code cia.keycloak.admin.enabled=true} so the bean
 * (and its provisioner dependency) stays absent in IT runs where admin is
 * disabled. Failures during provisioning are caught and logged at WARN
 * level — they MUST NOT block app startup. {@code UserController} returns
 * HTTP 503 until the underlying Keycloak issue is resolved, but the rest
 * of the application (which has nothing to do with Keycloak) keeps
 * serving.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cia.keycloak.admin", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class KeycloakTenantBootstrap implements ApplicationRunner {

    private final KeycloakTenantProvisioner provisioner;

    @Override
    public void run(ApplicationArguments args) {
        try {
            provisioner.provisionTargetRealm();
        } catch (RuntimeException e) {
            // Don't fail the app boot — user-facing endpoints that don't
            // touch Keycloak should keep working. UserController already
            // returns 503 when the admin client is unreachable; this just
            // logs the underlying cause so ops can investigate.
            log.warn("Tenant realm provisioning failed at startup — fanout / user-mgmt features may be degraded until resolved: {}",
                    e.getMessage());
        }
    }
}
