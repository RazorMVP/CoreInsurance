package com.nubeero.cia.portal.token;

/**
 * Performs the OAuth2 client-credentials grant against a partner app's
 * TENANT realm Keycloak token endpoint.
 *
 * <p>Implementations MUST NOT log the {@code clientSecret} parameter — only
 * {@code tenantRealm} / {@code clientId} may appear in log output. The
 * returned {@link MintedToken} never carries the secret.
 */
public interface ClientCredentialsTokenGrantor {

    /**
     * @param tenantRealm  the partner app's Keycloak tenant realm
     * @param clientId     the partner app's Keycloak client id
     * @param clientSecret the client's current secret, used for this grant call only
     * @return the minted access token + its expiry
     */
    MintedToken grant(String tenantRealm, String clientId, String clientSecret);
}
