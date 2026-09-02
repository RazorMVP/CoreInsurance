package com.nubeero.cia.portal.auth;

/**
 * The BFF's OAuth2 Authorization Code + PKCE client against the {@code partner} Keycloak realm.
 *
 * <p><b>The BFF — not the browser — is the OAuth client.</b> The browser only ever navigates
 * between {@code /portal/auth/login}, Keycloak's login page, and {@code /portal/auth/callback}; it
 * never sees an authorization code exchanged for a token, never holds a client secret (this is a
 * public/PKCE client — see {@code KeycloakTenantProvisioner.ensurePartnerPortalClient}), and never
 * receives a token in a response body. {@link #exchangeCode} is the one method that makes a real
 * network call; the other two are pure URL construction.
 *
 * <p>An injectable seam so integration tests can substitute a stub that returns canned tokens
 * without a live Keycloak — see {@code PortalAuthFlowIT}.
 */
public interface PortalOAuthClient {

    /**
     * Builds the {@code partner}-realm authorize URL the browser is redirected to, carrying the
     * PKCE {@code code_challenge} (S256) and the anti-CSRF {@code state}.
     *
     * @param state        opaque per-login value, echoed back on the callback and checked against
     *                     what {@code /portal/auth/login} stored server-side.
     * @param codeChallenge {@code BASE64URL(SHA256(verifier))} — see {@link PkceGenerator}.
     * @param redirectUri  the BFF's own absolute {@code /portal/auth/callback} URL — MUST be
     *                     byte-identical to the {@code redirect_uri} passed to {@link #exchangeCode}.
     */
    String buildAuthorizeUrl(String state, String codeChallenge, String redirectUri);

    /**
     * Exchanges an authorization code for tokens at the {@code partner}-realm token endpoint,
     * server-side. The only method on this interface that performs a network call.
     *
     * @param code         the authorization code Keycloak appended to the callback redirect.
     * @param codeVerifier the PKCE verifier generated alongside the challenge sent at login.
     * @param redirectUri  MUST match the {@code redirect_uri} used to build the authorize URL.
     */
    PortalOAuthTokens exchangeCode(String code, String codeVerifier, String redirectUri);

    /**
     * Builds the {@code partner}-realm RP-initiated logout URL. {@code idTokenHint} may be
     * {@code null} (this BFF does not persist the ID token in {@link
     * com.nubeero.cia.portal.session.PortalSession}) — Keycloak still accepts a {@code client_id}
     * + {@code post_logout_redirect_uri} logout request without it.
     */
    String buildLogoutUrl(String idTokenHint, String postLogoutRedirectUri);
}
