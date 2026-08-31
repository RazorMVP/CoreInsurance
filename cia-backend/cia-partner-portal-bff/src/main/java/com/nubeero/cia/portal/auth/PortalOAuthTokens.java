package com.nubeero.cia.portal.auth;

/**
 * Result of {@link PortalOAuthClient#exchangeCode}. Lives only on the server — the browser never
 * sees any field of this record; it is consumed once by {@link PortalAuthController#callback} to
 * build the {@link com.nubeero.cia.portal.session.PortalSession} stored server-side.
 *
 * @param accessToken  upstream Keycloak access token.
 * @param refreshToken upstream Keycloak refresh token.
 * @param idToken      upstream Keycloak ID token (may be {@code null} if the provider omits it);
 *                     not persisted into {@link com.nubeero.cia.portal.session.PortalSession} — RP
 *                     logout falls back to a {@code client_id}-only Keycloak logout URL without it.
 * @param email        the authenticated developer's email, extracted from the ID token claims.
 * @param displayName  the authenticated developer's display name, extracted from the ID token claims.
 */
public record PortalOAuthTokens(
        String accessToken,
        String refreshToken,
        String idToken,
        String email,
        String displayName) {
}
