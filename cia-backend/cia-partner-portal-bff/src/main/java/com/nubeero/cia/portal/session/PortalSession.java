package com.nubeero.cia.portal.session;

import java.time.Instant;
import java.util.UUID;

/**
 * Server-side session record for a Partner Portal developer.
 *
 * <p>The browser only ever holds an opaque cookie carrying {@link #id()} — never a JWT and never
 * either of the two tokens below. This record (the tokens included) lives exclusively in the
 * {@link PortalSessionStore} (in-memory for dev/IT, Redis in real deployments); it is never
 * serialized to the client.
 *
 * @param id             opaque 128-bit session identifier ({@code UUID.randomUUID().toString()}),
 *                       the only value the browser cookie carries.
 * @param partnerUserId  the authenticated partner developer.
 * @param email          the partner developer's email, denormalised for cheap display without a
 *                       second lookup.
 * @param displayName    the partner developer's display name, denormalised likewise.
 * @param accessToken    the upstream Keycloak access token for this developer's session.
 * @param refreshToken   the upstream Keycloak refresh token, used to silently renew the access
 *                       token as it nears expiry.
 * @param absoluteExpiry hard cutoff for the session regardless of activity — never extended.
 * @param idleExpiry     sliding cutoff extended by {@link PortalSessionStore#touch(String)} on
 *                       each authenticated request, capped at {@link #absoluteExpiry()}.
 * @param csrfToken      per-session CSRF token issued alongside the cookie and required on every
 *                       state-changing request.
 */
public record PortalSession(
        String id,
        UUID partnerUserId,
        String email,
        String displayName,
        String accessToken,
        String refreshToken,
        Instant absoluteExpiry,
        Instant idleExpiry,
        String csrfToken) {
}
