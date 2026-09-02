package com.nubeero.cia.portal.apps;

import java.util.List;

/**
 * Keycloak-sourced enrichment for a Partner App's row in {@code GET /portal/apps} — the
 * client's default + optional client scopes as configured in its tenant realm.
 *
 * <p>Deliberately thin: everything else {@link PortalAppSummary} needs (rate tier, active
 * status, tenant label) is DB-sourced (see {@link PortalAppsService}) — this record carries only
 * the piece that genuinely lives in Keycloak, not the tenant database.
 */
public record PartnerAppKeycloakMetadata(String clientId, List<String> scopes) {

    /**
     * Best-effort fallback when the Keycloak admin client is disabled or the client can't be
     * found in its tenant realm — never fatal to the {@code /portal/apps} read (see
     * {@link KeycloakPartnerAppMetadataResolver}).
     */
    public static PartnerAppKeycloakMetadata empty(String clientId) {
        return new PartnerAppKeycloakMetadata(clientId, List.of());
    }
}
