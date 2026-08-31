package com.nubeero.cia.portal.auth;

import java.util.UUID;

/**
 * The authenticated principal {@link PortalSessionFilter} attaches to the request's
 * {@code SecurityContext} for every {@code /portal/**} request carrying a valid session cookie.
 * Downstream {@code /portal/**} controllers read it via {@code @AuthenticationPrincipal
 * PortalPrincipal}.
 *
 * <p>Deliberately thin — it carries only what a controller needs to act on behalf of the caller
 * (identity) and to enforce CSRF double-submit ({@link #csrfToken()}), never the session's tokens.
 */
public record PortalPrincipal(UUID partnerUserId, String email, String csrfToken) {
}
