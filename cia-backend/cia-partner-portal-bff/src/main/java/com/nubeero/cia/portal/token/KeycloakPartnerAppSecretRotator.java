package com.nubeero.cia.portal.token;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Production {@link PartnerAppSecretRotator} — regenerates a partner app's {@code client_secret}
 * via the shared Keycloak admin client (same conditional {@code Keycloak} bean {@link
 * KeycloakAdminPartnerClientSecretResolver} uses).
 *
 * <p>{@code realm.clients().get(internalId).generateNewSecret()} is Keycloak's own
 * regenerate-secret admin operation — the OLD secret is invalidated server-side the moment this
 * call returns, so any token grant still using it fails on its next attempt. This class returns
 * the new value exactly once and never logs it (mirrors {@link KeycloakAdminPartnerClientSecretResolver}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeycloakPartnerAppSecretRotator implements PartnerAppSecretRotator {

    private final ObjectProvider<Keycloak> keycloak;

    @Override
    public String rotateSecret(String tenantRealm, String clientId) {
        Keycloak client = keycloak.getIfAvailable();
        if (client == null) {
            throw new PartnerAppTokenException("PARTNER_APP_SECRET_UNAVAILABLE",
                    "Keycloak admin client is disabled — cannot rotate the partner app secret for realm '"
                            + tenantRealm + "'. Set cia.keycloak.admin.enabled=true.");
        }

        RealmResource realm = client.realm(tenantRealm);
        List<ClientRepresentation> found = realm.clients().findByClientId(clientId);
        if (found.isEmpty()) {
            throw new PartnerAppTokenException("PARTNER_APP_CLIENT_NOT_FOUND",
                    "No Keycloak client '" + clientId + "' found in tenant realm '" + tenantRealm + "'.");
        }

        String internalId = found.get(0).getId();
        CredentialRepresentation newSecret = realm.clients().get(internalId).generateNewSecret();
        // Deliberately log only the coordinates, never the secret value.
        log.info("Rotated client_secret for partner app '{}' in tenant realm '{}'", clientId, tenantRealm);
        return newSecret.getValue();
    }
}
