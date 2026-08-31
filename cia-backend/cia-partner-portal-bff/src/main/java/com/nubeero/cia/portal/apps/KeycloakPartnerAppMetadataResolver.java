package com.nubeero.cia.portal.apps;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Production {@link PartnerAppKeycloakMetadataResolver} — reads a Partner App's default +
 * optional client scopes from its TENANT realm via the shared Keycloak admin client (the same
 * conditional {@code Keycloak} bean {@code UserService} / {@code
 * KeycloakAdminPartnerClientSecretResolver} use; see cia-setup's {@code KeycloakAdminConfig}).
 *
 * <p>{@code ObjectProvider} because the {@code Keycloak} bean is gated on
 * {@code cia.keycloak.admin.enabled} — dev environments without a reachable Keycloak instance
 * never create it. <strong>Deliberately non-fatal</strong> on every failure path (admin client
 * disabled, client not found in the realm, any Keycloak call error): this is enrichment for a
 * display list, not a hard dependency — a Keycloak hiccup must never turn {@code GET /portal/apps}
 * into a 5xx. {@link PortalAppsService} falls back to the Partner App's own DB {@code scopes}
 * column whenever this returns an empty list.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeycloakPartnerAppMetadataResolver implements PartnerAppKeycloakMetadataResolver {

    private final ObjectProvider<Keycloak> keycloak;

    @Override
    public PartnerAppKeycloakMetadata resolve(String tenantRealm, String clientId) {
        Keycloak client = keycloak.getIfAvailable();
        if (client == null) {
            log.debug("Keycloak admin client disabled — skipping scope enrichment for client '{}' in realm '{}'",
                    clientId, tenantRealm);
            return PartnerAppKeycloakMetadata.empty(clientId);
        }
        try {
            RealmResource realm = client.realm(tenantRealm);
            List<ClientRepresentation> found = realm.clients().findByClientId(clientId);
            if (found.isEmpty()) {
                log.warn("No Keycloak client '{}' found in tenant realm '{}' — scope enrichment skipped",
                        clientId, tenantRealm);
                return PartnerAppKeycloakMetadata.empty(clientId);
            }
            ClientResource clientResource = realm.clients().get(found.get(0).getId());
            List<String> scopes = new ArrayList<>();
            clientResource.getDefaultClientScopes().forEach(scope -> scopes.add(scope.getName()));
            clientResource.getOptionalClientScopes().forEach(scope -> scopes.add(scope.getName()));
            return new PartnerAppKeycloakMetadata(clientId, scopes);
        } catch (RuntimeException e) {
            log.warn("Failed to resolve Keycloak scope metadata for client '{}' in realm '{}': {}",
                    clientId, tenantRealm, e.getMessage());
            return PartnerAppKeycloakMetadata.empty(clientId);
        }
    }
}
