package com.nubeero.cia.portal.apps;

import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.partner.app.PartnerApp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads a single {@link PartnerApp} row from ITS OWN tenant schema, bypassing the request-scoped
 * OSIV-shared {@link EntityManager}.
 *
 * <h2>Why a raw {@link EntityManager}, not {@code PartnerAppRepository}</h2>
 * {@code spring.jpa.open-in-view} is unset everywhere in this codebase, so it defaults to Spring
 * Boot's {@code true} — one Hibernate {@code Session} is opened for the WHOLE HTTP request (bound
 * via {@code OpenEntityManagerInViewInterceptor}) and every {@code @PersistenceContext}/Spring
 * Data repository call within that request transparently reuses it. Hibernate resolves {@code
 * CurrentTenantIdentifierResolver.resolveCurrentTenantIdentifier()} exactly ONCE, when that shared
 * {@code Session} is first created — so a Spring Data repository call (which looks up the
 * request's already-bound {@code EntityManager} instead of making a new one) would silently keep
 * using the tenant identifier resolved for the FIRST query of the request (typically {@code
 * "public"}, from a grant lookup), ignoring every later {@link TenantContext#setTenantId} call
 * entirely. {@link #read} sidesteps this by calling {@link EntityManagerFactory#createEntityManager()}
 * directly — a brand-new physical {@code Session}, independent of the OSIV-bound one, which
 * resolves the tenant identifier fresh (against whatever {@link TenantContext} holds at THAT
 * moment) and is closed immediately after the single read.
 *
 * <p>Extracted from {@code PortalAppsService} (Task 7) for Task 8's proxy/webhook/credential-rotate
 * endpoints, which all need this exact same per-app tenant-schema read before they can resolve a
 * {@code clientId} to mint a token against. Two-plus call sites — the threshold Task 7's report
 * flagged as the point where this pattern should stop being copy-pasted and start being shared.
 */
@Component
@RequiredArgsConstructor
public class TenantScopedPartnerAppReader {

    /**
     * The registry/public schema every {@code /portal/**} request is pinned to before and after
     * this read — mirrors {@code PortalSessionFilter.REGISTRY_TENANT_ID}. Duplicated (not shared)
     * because that constant is package-private to {@code com.nubeero.cia.portal.auth}; the literal
     * itself is what matters here, not a cross-package reference to it.
     */
    private static final String REGISTRY_TENANT_ID = "public";

    private final EntityManagerFactory entityManagerFactory;

    /**
     * @return the {@link PartnerApp}, or {@code null} if it doesn't exist (in that schema) or is
     *         soft-deleted. The {@code try/finally} always restores {@link TenantContext} to
     *         {@link #REGISTRY_TENANT_ID} and closes the dedicated {@code EntityManager},
     *         regardless of whether the lookup found the app or threw.
     */
    public PartnerApp read(String tenantSchema, UUID partnerAppId) {
        TenantContext.setTenantId(tenantSchema);
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            PartnerApp app = em.find(PartnerApp.class, partnerAppId);
            return (app != null && app.getDeletedAt() == null) ? app : null;
        } finally {
            em.close();
            TenantContext.setTenantId(REGISTRY_TENANT_ID);
        }
    }
}
