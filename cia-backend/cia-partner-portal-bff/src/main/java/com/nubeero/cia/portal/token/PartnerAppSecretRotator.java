package com.nubeero.cia.portal.token;

/**
 * Rotates a Partner App's Keycloak {@code client_secret} — injectable seam so {@code
 * PortalProxyService}'s {@code POST /portal/apps/{id}/credentials/rotate} can be tested without a
 * live Keycloak admin client, mirroring {@link PartnerClientSecretResolver} /
 * {@link ClientCredentialsTokenGrantor}.
 */
public interface PartnerAppSecretRotator {

    /**
     * Generates and persists a brand-new {@code client_secret} for {@code clientId} in {@code
     * tenantRealm}, returning the new value. The OLD secret stops working immediately — Keycloak
     * only ever honours the current secret for a client-credentials grant.
     *
     * @return the new secret. Callers must return this to the caller EXACTLY ONCE and never
     *         persist or log it — same invariant as every other secret in this package.
     */
    String rotateSecret(String tenantRealm, String clientId);
}
