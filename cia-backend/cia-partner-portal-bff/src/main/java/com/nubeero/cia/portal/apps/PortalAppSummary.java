package com.nubeero.cia.portal.apps;

import com.nubeero.cia.portal.grant.GrantRole;

import java.util.List;
import java.util.UUID;

/**
 * A single Partner App the current Partner Portal developer may manage — one entry per active
 * {@link com.nubeero.cia.portal.grant.PartnerPortalGrant}, returned by {@code GET /portal/apps}
 * as the app-context selector for the SPA.
 *
 * @param partnerAppId  the Partner App's id (in ITS OWN tenant schema — see
 *                       {@link PortalAppsService} for how it's read cross-tenant).
 * @param clientId      the app's OAuth2 {@code client_id}.
 * @param tenantSchema  the tenant schema the app lives in (== its Keycloak tenant realm name).
 * @param tenantLabel   the tenant's display name, from {@code public.tenants}.
 * @param scopes        the app's granted OAuth2 scopes — Keycloak-sourced when available,
 *                       falling back to the app's own DB {@code scopes} column otherwise.
 * @param rateTier      the app's {@code PartnerPlan} (SANDBOX / STARTER / GROWTH / ENTERPRISE).
 * @param status        {@code "ACTIVE"} / {@code "INACTIVE"}, from the app's {@code active} flag.
 * @param role          the current developer's {@link GrantRole} on this app.
 */
public record PortalAppSummary(
        UUID partnerAppId,
        String clientId,
        String tenantSchema,
        String tenantLabel,
        List<String> scopes,
        String rateTier,
        String status,
        GrantRole role) {
}
