package com.nubeero.cia.portal.proxy.dto;

/**
 * {@code POST /portal/apps/{id}/credentials/rotate} response — the new {@code client_secret},
 * returned EXACTLY ONCE. Never persisted by this service (Keycloak is the sole system of record for
 * it) and never logged; the developer must copy it now or rotate again.
 */
public record PortalAppCredentialsRotateResponse(String clientId, String clientSecret) {
}
