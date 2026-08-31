package com.nubeero.cia.portal.apps;

import com.nubeero.cia.portal.grant.GrantRole;
import com.nubeero.cia.portal.grant.PartnerPortalGrant;
import com.nubeero.cia.portal.grant.PartnerPortalGrantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Answers "does THIS partner developer hold an active grant for THIS Partner App, and are they a
 * {@code MANAGER}?" — the single authorization gate every {@code /portal/apps/{id}/**} endpoint
 * (Tasks 8 + 9) sits behind.
 *
 * <p>Reads against {@code public.partner_portal_grant} — the registry table {@code
 * PortalSessionFilter} already pins {@code TenantContext} to {@code "public"} for on every
 * {@code /portal/**} request, so no tenant-context handling is needed here. Contrast with {@link
 * PortalAppsService}, which additionally has to hop into each app's OWN tenant schema to read the
 * {@code PartnerApp} row itself.
 */
@Service
@RequiredArgsConstructor
public class GrantAuthorizationService {

    private final PartnerPortalGrantRepository grantRepository;

    /**
     * @throws PortalAccessDeniedException (403) if {@code partnerUserId} has no active grant on
     *                                      {@code partnerAppId}.
     * @return the active grant, for callers (Tasks 8 + 9) that need {@code tenantSchema} /
     *         {@code role} to proceed.
     */
    @Transactional(readOnly = true)
    public PartnerPortalGrant assertGrant(UUID partnerUserId, UUID partnerAppId) {
        return grantRepository.findByPartnerUserIdAndPartnerAppIdAndDeletedAtIsNull(partnerUserId, partnerAppId)
                .orElseThrow(() -> new PortalAccessDeniedException(
                        "No active grant for partner app " + partnerAppId));
    }

    /**
     * As {@link #assertGrant}, additionally requiring the grant's {@link GrantRole} to be {@code
     * MANAGER}.
     *
     * @throws PortalAccessDeniedException (403) if there is no active grant, or the active grant
     *                                      is {@code VIEWER}.
     */
    @Transactional(readOnly = true)
    public PartnerPortalGrant assertManager(UUID partnerUserId, UUID partnerAppId) {
        PartnerPortalGrant grant = assertGrant(partnerUserId, partnerAppId);
        if (grant.getRole() != GrantRole.MANAGER) {
            throw new PortalAccessDeniedException(
                    "MANAGER role required for partner app " + partnerAppId);
        }
        return grant;
    }
}
