package com.nubeero.cia.portal.proxy.dto;

import java.util.List;

/**
 * {@code GET /portal/apps/{id}/credentials} response — the app's Keycloak {@code client_id} and
 * granted scopes. Never the {@code client_secret}: this endpoint exists so a developer can copy
 * their {@code client_id} and see what they're scoped for, not read a live secret back out.
 */
public record PortalAppCredentialsResponse(String clientId, List<String> scopes) {
}
