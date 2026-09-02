package com.nubeero.cia.portal.apps;

import com.nubeero.cia.partner.app.PartnerApp;
import com.nubeero.cia.portal.grant.PartnerPortalGrant;
import com.nubeero.cia.portal.grant.PartnerPortalGrantRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Builds the {@code GET /portal/apps} response: every Partner App the current developer holds an
 * active grant on, enriched with the app's own data.
 *
 * <h2>Cross-tenant per-app data access</h2>
 * {@code /portal/**} requests are pinned to the {@code "public"} tenant context by {@code
 * PortalSessionFilter} — the grant lookup ({@link #listApps}'s first call, against {@code
 * public.partner_portal_grant}) works under that pin. But each grant points at a Partner App that
 * lives in a DIFFERENT tenant's schema ({@link PartnerPortalGrant#getTenantSchema()}). Reading
 * that app's own DB row requires temporarily repointing {@code TenantContext} at the grant's
 * tenant — see {@link TenantScopedPartnerAppReader} — so {@code MultiTenantConnectionProvider}
 * borrows a connection whose {@code search_path} resolves the right schema's {@code partner_apps} table.
 * Every switch is paired with a {@code try/finally} restore back to {@code "public"} — the
 * registry scoping the rest of the {@code /portal/**} request plane depends on (and what any
 * later grant in the same loop, or a later call in this same request such as Task 8/9's {@code
 * GrantAuthorizationService}, needs to find itself back in).
 *
 * <p>Tenant label ({@code public.tenants}) and Keycloak client scopes are resolved WITHOUT this
 * switch — {@link PortalTenantLabelLookup} fully qualifies {@code public.tenants} regardless of
 * search_path, and {@link PartnerAppKeycloakMetadataResolver} is realm-scoped over the Keycloak
 * admin API, not a DB tenant read at all.
 *
 * <h2>Why a raw {@code EntityManager}, not {@code PartnerAppRepository}</h2>
 * {@code spring.jpa.open-in-view} is unset everywhere in this codebase, so it defaults to Spring
 * Boot's {@code true} — one Hibernate {@code Session} is opened for the WHOLE HTTP request (bound
 * via {@code OpenEntityManagerInViewInterceptor}) and every {@code @PersistenceContext}/Spring
 * Data repository call within that request transparently reuses it. Hibernate resolves {@code
 * CurrentTenantIdentifierResolver.resolveCurrentTenantIdentifier()} exactly ONCE, when that shared
 * {@code Session} is first created — so a Spring Data repository call (which looks up the
 * request's already-bound {@code EntityManager} instead of making a new one) would silently keep
 * using the tenant identifier resolved for the FIRST query of the request (here: {@code "public"},
 * from the grants read), ignoring every later {@code TenantContext.setTenantId} call entirely.
 * {@link TenantScopedPartnerAppReader} sidesteps this by calling {@code
 * EntityManagerFactory.createEntityManager()} directly — a brand-new physical {@code Session},
 * independent of the OSIV-bound one, which resolves the tenant identifier fresh (against whatever
 * {@code TenantContext} holds at THAT moment) and is closed immediately after the single read.
 * Proven by {@code PortalAppsIT}, whose two-tenant scenario 500s with a Spring Data repository
 * (wrong-schema "relation does not exist") and passes with this pattern. Extracted into {@link
 * TenantScopedPartnerAppReader} in Task 8, which needs the identical read at 2+ call sites of its
 * own (webhooks proxy, credentials, credentials/rotate).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalAppsService {

    private final PartnerPortalGrantRepository grantRepository;
    private final TenantScopedPartnerAppReader appReader;
    private final PartnerAppKeycloakMetadataResolver keycloakMetadataResolver;
    private final PortalTenantLabelLookup tenantLabelLookup;

    /** Every active-grant Partner App for {@code partnerUserId}, enriched for the SPA's app-context selector. */
    public List<PortalAppSummary> listApps(UUID partnerUserId) {
        List<PartnerPortalGrant> grants = grantRepository.findByPartnerUserIdAndDeletedAtIsNull(partnerUserId);
        List<PortalAppSummary> summaries = new ArrayList<>();
        for (PartnerPortalGrant grant : grants) {
            PortalAppSummary summary = enrich(grant);
            if (summary != null) {
                summaries.add(summary);
            }
        }
        return summaries;
    }

    private PortalAppSummary enrich(PartnerPortalGrant grant) {
        String tenantSchema = grant.getTenantSchema();
        PartnerApp app = appReader.read(tenantSchema, grant.getPartnerAppId());
        if (app == null) {
            log.warn("Grant {} points at a missing/deleted PartnerApp {} in tenant '{}' — excluding it "
                            + "from GET /portal/apps",
                    grant.getId(), grant.getPartnerAppId(), tenantSchema);
            return null;
        }

        String tenantLabel = tenantLabelLookup.labelFor(tenantSchema).orElse(tenantSchema);
        PartnerAppKeycloakMetadata keycloakMetadata = keycloakMetadataResolver.resolve(tenantSchema, app.getClientId());
        List<String> scopes = keycloakMetadata.scopes().isEmpty()
                ? splitScopes(app.getScopes())
                : keycloakMetadata.scopes();

        return new PortalAppSummary(
                app.getId(),
                app.getClientId(),
                tenantSchema,
                tenantLabel,
                scopes,
                app.getPlan().name(),
                app.isActive() ? "ACTIVE" : "INACTIVE",
                grant.getRole());
    }

    private static List<String> splitScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return List.of();
        }
        return List.of(scopes.trim().split("\\s+"));
    }
}
