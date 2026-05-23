package com.nubeero.cia.setup.keycloak;

import com.nubeero.cia.setup.user.KeycloakAdminProperties;
import jakarta.ws.rs.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * F1e-tenant-provisioning (S119) — idempotent Keycloak realm bootstrapper.
 *
 * <p>Ensures the tenant realm exists and carries the realm-level settings
 * the CIA application requires. Called on application startup by
 * {@link KeycloakTenantBootstrap}; can be invoked explicitly from a CLI
 * argument handler to repair an existing realm.
 *
 * <p>Currently enforces:
 * <ol>
 *   <li>Realm exists (created with defaults if missing).</li>
 *   <li>{@code UnmanagedAttributePolicy=ENABLED} on the user-profile config.
 *       Without this, Keycloak 24's default {@code DISABLED} policy silently
 *       drops the implicit {@code accessGroupId} attribute that
 *       {@code UserService.create} writes — breaking the F1e-sync-AccessGroup-fanout
 *       lookup. Surfaced empirically by the S118 Testcontainers IT suite.</li>
 * </ol>
 *
 * <p>Future realm-level invariants (custom realm attributes, default
 * client scopes, role hierarchy seed, etc.) extend the same idempotent
 * pattern — read current state, mutate only if needed, write back.
 *
 * <p>Encapsulation. Same pattern as {@link KeycloakRealmRoleSyncer} +
 * {@link KeycloakPasswordPolicySyncer}: every Keycloak admin-client type
 * reference lives inside this class. Callers see it as a plain Spring
 * service. Conditional on {@code cia.keycloak.admin.enabled=true} so the
 * bean (and its Keycloak class graph) stays absent in IT runs where admin
 * is disabled.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "cia.keycloak.admin", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class KeycloakTenantProvisioner {

    private final ObjectProvider<Keycloak>  keycloak;
    private final KeycloakAdminProperties   props;

    /**
     * Idempotent. Safe to call on every application boot.
     *
     * <p>If the Keycloak admin client is unavailable at runtime (rare:
     * {@code cia.keycloak.admin.enabled=true} but the client failed to
     * resolve at startup), logs a warning and returns — the DB / config
     * is the source of truth, and the next call retries.
     */
    public void provisionTenantRealm(String realmName) {
        Keycloak client = keycloak.getIfAvailable();
        if (client == null) {
            log.warn("Keycloak admin client unavailable — skipping tenant realm provisioning for {}", realmName);
            return;
        }
        ensureRealm(client, realmName);
        ensureUnmanagedAttributePolicy(client, realmName);
    }

    /**
     * Convenience overload — provisions the realm named by
     * {@link KeycloakAdminProperties#getTargetRealm()}.
     */
    public void provisionTargetRealm() {
        provisionTenantRealm(props.getTargetRealm());
    }

    private void ensureRealm(Keycloak client, String realmName) {
        try {
            client.realm(realmName).toRepresentation();
            log.info("Tenant realm '{}' exists", realmName);
        } catch (NotFoundException nfe) {
            RealmRepresentation rep = new RealmRepresentation();
            rep.setRealm(realmName);
            rep.setEnabled(true);
            client.realms().create(rep);
            log.info("Tenant realm '{}' created", realmName);
        }
    }

    private void ensureUnmanagedAttributePolicy(Keycloak client, String realmName) {
        UPConfig upc = client.realm(realmName).users().userProfile().getConfiguration();
        UPConfig.UnmanagedAttributePolicy current = upc.getUnmanagedAttributePolicy();
        if (current != UPConfig.UnmanagedAttributePolicy.ENABLED) {
            upc.setUnmanagedAttributePolicy(UPConfig.UnmanagedAttributePolicy.ENABLED);
            client.realm(realmName).users().userProfile().update(upc);
            log.info("Tenant realm '{}' — set UnmanagedAttributePolicy=ENABLED (was {})", realmName, current);
        } else {
            log.debug("Tenant realm '{}' — UnmanagedAttributePolicy already ENABLED", realmName);
        }
    }
}
