package com.nubeero.cia.portal.token;

import java.time.Instant;

/**
 * A minted OAuth2 client-credentials access token for one partner app,
 * scoped to a single {@code (tenantRealm, clientId)} pair.
 *
 * <p>Deliberately carries ONLY the access token and its expiry. The
 * {@code client_secret} used to mint it is fetched from Keycloak
 * just-in-time by {@link PartnerAppTokenService}, used for exactly one
 * client-credentials grant call, and discarded — it is never persisted,
 * never returned to any caller, and never logged. This record must never
 * grow a field that carries the secret.
 */
public record MintedToken(String accessToken, Instant expiry) {
}
