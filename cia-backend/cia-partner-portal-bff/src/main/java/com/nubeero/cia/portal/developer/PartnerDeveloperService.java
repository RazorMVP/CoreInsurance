package com.nubeero.cia.portal.developer;

import com.nubeero.cia.common.exception.CiaException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.partner.app.PartnerAppService;
import com.nubeero.cia.portal.developer.dto.InviteDeveloperRequest;
import com.nubeero.cia.portal.developer.dto.PartnerDeveloperGrantResponse;
import com.nubeero.cia.portal.grant.PartnerPortalGrant;
import com.nubeero.cia.portal.grant.PartnerPortalGrantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Writes/reads the cross-tenant {@code public.partner_portal_grant} registry on behalf of the
 * internal, System-Admin-gated "invite developer" endpoints ({@link PartnerDeveloperController}).
 *
 * <h2>{@code partner_user_id} derivation (demo-first)</h2>
 * The {@code partner} Keycloak realm is off by default (Task 2 — gated
 * {@code PartnerPortalBootstrapRunner}), so there is usually no real Keycloak user to look up at
 * invite time. Per the Sub-project A ruling, {@code partnerUserId} is derived deterministically
 * from the lowercased email via {@link UUID#nameUUIDFromBytes(byte[])} — the same email always
 * yields the same id, so a grant created before the developer's first login lines up with the
 * identity Keycloak (or the eventual first-login reconciliation) resolves later. Live
 * Keycloak-{@code partner}-realm user provisioning/lookup (preferring the real {@code sub} once
 * the realm is provisioned + {@code cia.keycloak.admin.enabled}) is intentionally deferred — it is
 * out of scope for this task and does not change the registry row shape.
 */
@Service
@RequiredArgsConstructor
public class PartnerDeveloperService {

    private final PartnerPortalGrantRepository grantRepository;
    private final PartnerAppService partnerAppService;

    @Transactional
    public PartnerDeveloperGrantResponse invite(UUID partnerAppId, InviteDeveloperRequest request, String actor) {
        // Confirms the app exists — tenant-scoped automatically via the connection's search_path,
        // so this also proves the app belongs to the caller's own tenant.
        partnerAppService.findOrThrow(partnerAppId);

        String email = request.email().trim().toLowerCase(Locale.ROOT);
        UUID partnerUserId = derivePartnerUserId(email);

        grantRepository.findByPartnerUserIdAndPartnerAppIdAndDeletedAtIsNull(partnerUserId, partnerAppId)
                .ifPresent(existing -> {
                    throw new CiaException("DUPLICATE_GRANT",
                            "Developer " + email + " already has an active grant on this partner app",
                            HttpStatus.CONFLICT);
                });

        PartnerPortalGrant grant = new PartnerPortalGrant();
        grant.setPartnerUserId(partnerUserId);
        grant.setPartnerUserEmail(email);
        grant.setTenantSchema(requireTenantId());
        grant.setPartnerAppId(partnerAppId);
        grant.setRole(request.role());
        grant.setCreatedBy(actor);

        return PartnerDeveloperGrantResponse.from(grantRepository.save(grant));
    }

    @Transactional(readOnly = true)
    public List<PartnerDeveloperGrantResponse> list(UUID partnerAppId) {
        partnerAppService.findOrThrow(partnerAppId);
        return grantRepository.findByPartnerAppIdAndDeletedAtIsNull(partnerAppId).stream()
                .map(PartnerDeveloperGrantResponse::from)
                .toList();
    }

    @Transactional
    public void revoke(UUID partnerAppId, UUID grantId, String actor) {
        // Ownership guard — mirrors invite()/list(): confirms the app exists AND belongs to the
        // caller's own tenant (tenant-scoped via search_path) BEFORE touching the shared
        // public.partner_portal_grant registry. Without this, a Tenant-A admin who knows/guesses
        // a (partnerAppId, grantId) pair belonging to Tenant-B could soft-delete that grant — the
        // registry table is reachable from every tenant connection.
        partnerAppService.findOrThrow(partnerAppId);

        PartnerPortalGrant grant = grantRepository.findById(grantId)
                .filter(g -> partnerAppId.equals(g.getPartnerAppId()) && g.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("PartnerPortalGrant", grantId));
        grant.setDeletedAt(Instant.now());
        grantRepository.save(grant);
    }

    static UUID derivePartnerUserId(String lowercasedEmail) {
        return UUID.nameUUIDFromBytes(lowercasedEmail.getBytes(StandardCharsets.UTF_8));
    }

    private static String requireTenantId() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            // Should never happen on a real Bearer-authenticated request — TenantContextFilter
            // populates TenantContext for every Jwt-principal request before the controller runs.
            throw new CiaException("TENANT_CONTEXT_MISSING",
                    "No tenant could be resolved for the current request", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return tenantId;
    }
}
