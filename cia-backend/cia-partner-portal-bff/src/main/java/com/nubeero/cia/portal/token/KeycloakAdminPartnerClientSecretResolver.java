package com.nubeero.cia.portal.token;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Production {@link PartnerClientSecretResolver} — reads a partner app's
 * {@code client_secret} from its TENANT realm via the shared Keycloak admin
 * client (the same conditional {@code Keycloak} bean {@code UserService}
 * uses for user CRUD; see cia-setup's {@code KeycloakAdminConfig}).
 *
 * <p>{@code ObjectProvider} because the {@code Keycloak} bean is gated on
 * {@code cia.keycloak.admin.enabled} — dev environments without a reachable
 * Keycloak instance never create it. Mirrors the same injection idiom used
 * throughout cia-setup ({@code UserService}, {@code KeycloakTenantProvisioner}).
 *
 * <p>Fetches fresh on every call — never caches the secret itself. {@link
 * PartnerAppTokenService} is the only caller and it invokes this exactly
 * once per mint, discarding the value immediately after the grant call.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeycloakAdminPartnerClientSecretResolver implements PartnerClientSecretResolver {

    private final ObjectProvider<Keycloak> keycloak;

    @Override
    public String resolveSecret(String tenantRealm, String clientId) {
        Keycloak client = keycloak.getIfAvailable();
        if (client == null) {
            throw new PartnerAppTokenException("PARTNER_APP_SECRET_UNAVAILABLE",
                    "Keycloak admin client is disabled — cannot fetch the partner app secret for realm '"
                            + tenantRealm + "'. Set cia.keycloak.admin.enabled=true.");
        }

        RealmResource realm = client.realm(tenantRealm);
        List<ClientRepresentation> found = realm.clients().findByClientId(clientId);
        if (found.isEmpty()) {
            throw new PartnerAppTokenException("PARTNER_APP_CLIENT_NOT_FOUND",
                    "No Keycloak client '" + clientId + "' found in tenant realm '" + tenantRealm + "'.");
        }

        String internalId = found.get(0).getId();
        String secret = realm.clients().get(internalId).getSecret().getValue();
        if (secret == null || secret.isBlank()) {
            // Happens when the Keycloak client is mistakenly PUBLIC (no client_secret to fetch) —
            // fail with a clear, typed error instead of letting a null propagate into an NPE
            // downstream in PartnerAppTokenService's client-credentials grant call.
            throw new PartnerAppTokenException("PARTNER_APP_SECRET_MISSING",
                    "Keycloak client '" + clientId + "' in tenant realm '" + tenantRealm
                            + "' has no client_secret — is it mistakenly configured as a PUBLIC client?");
        }
        // Deliberately log only the coordinates, never the secret value.
        log.debug("Resolved client_secret for partner app '{}' in tenant realm '{}'", clientId, tenantRealm);
        return secret;
    }
}
