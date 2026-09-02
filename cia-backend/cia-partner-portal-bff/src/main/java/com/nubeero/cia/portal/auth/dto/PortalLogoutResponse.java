package com.nubeero.cia.portal.auth.dto;

/**
 * {@code POST /portal/auth/logout} response body. The server-side session is already cleared (and
 * the cookie already expired) by the time this is returned; {@code logoutUrl} is the Keycloak
 * RP-initiated logout URL the SPA should navigate the browser to next, to also end the upstream
 * Keycloak session.
 */
public record PortalLogoutResponse(String logoutUrl) {
}
