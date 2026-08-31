package com.nubeero.cia.portal.token;

/**
 * Fetches a partner app's OAuth2 {@code client_secret} from Keycloak,
 * just-in-time, for exactly one client-credentials grant call.
 *
 * <p>Implementations MUST NOT cache, persist, or log the returned secret.
 * {@link PartnerAppTokenService} is the only caller and discards the value
 * immediately after passing it to {@link ClientCredentialsTokenGrantor}.
 */
public interface PartnerClientSecretResolver {

    /**
     * @param tenantRealm the partner app's Keycloak tenant realm
     * @param clientId    the partner app's Keycloak client id
     * @return the client's current secret — never cached or logged by the caller
     */
    String resolveSecret(String tenantRealm, String clientId);
}
