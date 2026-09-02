package com.nubeero.cia.portal.apps;

/**
 * Resolves a Partner App's Keycloak client scopes, realm-scoped (no DB tenant needed to make the
 * call itself — the caller already knows which tenant realm to ask). Injectable seam: the real
 * {@link KeycloakPartnerAppMetadataResolver} talks to the shared Keycloak admin client (mirrors
 * {@code cia.portal.token.PartnerClientSecretResolver}); tests substitute a canned stub so
 * {@code GET /portal/apps} can be exercised with no live Keycloak.
 */
public interface PartnerAppKeycloakMetadataResolver {

    /**
     * @param tenantRealm the Partner App's tenant realm (== {@code PartnerPortalGrant.tenantSchema}
     *                     — realm-per-tenant, same convention {@code PartnerAppTokenService} uses)
     * @param clientId    the Partner App's Keycloak client id
     * @return scopes enrichment; {@link PartnerAppKeycloakMetadata#empty} on any failure — never
     *         throws, since this is enrichment, not a hard dependency of the apps list.
     */
    PartnerAppKeycloakMetadata resolve(String tenantRealm, String clientId);
}
