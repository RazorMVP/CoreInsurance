package com.nubeero.cia.portal.usage;

import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.partner.app.PartnerApp;
import com.nubeero.cia.partner.usage.PartnerRequestDaily;
import com.nubeero.cia.partner.usage.PartnerUsageRollupStore;
import com.nubeero.cia.partner.webhook.WebhookRegistration;
import com.nubeero.cia.portal.apps.GrantAuthorizationService;
import com.nubeero.cia.portal.grant.PartnerPortalGrant;
import com.nubeero.cia.portal.proxy.PortalAppNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Builds the {@code GET /portal/apps/{id}/usage} response: request-volume telemetry (live "today"
 * from {@link PartnerUsageRollupStore} + durable "history" from {@link PartnerRequestDaily}) and
 * webhook delivery health, composed for a single Partner App.
 *
 * <h2>Cross-tenant read pattern</h2>
 * Every read this endpoint needs — the app row itself, its request-daily history, its webhook
 * registrations, and their delivery logs — lives inside the app's OWN tenant schema, not the
 * {@code "public"} registry {@code PortalSessionFilter} pins the request to. This mirrors {@link
 * com.nubeero.cia.portal.apps.TenantScopedPartnerAppReader}'s rationale exactly (a Spring Data
 * repository call would silently keep reusing the OSIV-bound session's FIRST-resolved tenant
 * identifier — {@code "public"}, from the grant lookup — regardless of any later {@link
 * TenantContext#setTenantId} call). Unlike that single-purpose reader, this method needs SEVERAL
 * queries against the same tenant schema, so it opens ONE dedicated {@link EntityManager} via
 * {@link EntityManagerFactory#createEntityManager()} for the whole tenant-scoped read and runs
 * every query through it before closing — cheaper than one fresh EM per query, and still correct
 * (the tenant identifier is resolved once, when this EM's session is first used, against whatever
 * {@link TenantContext} holds at that moment — which is set once, right before, and never changed
 * again until the {@code finally} restores it).
 */
@Service
@RequiredArgsConstructor
public class PortalUsageService {

    private static final String REGISTRY_TENANT_ID = "public";
    private static final int HISTORY_DAYS = 30;

    private final GrantAuthorizationService grantAuthorizationService;
    private final EntityManagerFactory entityManagerFactory;
    private final PartnerUsageRollupStore rollupStore;

    public PortalUsageResponse usage(UUID partnerUserId, UUID partnerAppId) {
        PartnerPortalGrant grant = grantAuthorizationService.assertGrant(partnerUserId, partnerAppId);
        String tenantSchema = grant.getTenantSchema();

        TenantContext.setTenantId(tenantSchema);
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            PartnerApp app = em.find(PartnerApp.class, partnerAppId);
            if (app == null || app.getDeletedAt() != null) {
                throw new PortalAppNotFoundException(
                        "Partner app " + partnerAppId + " not found in tenant '" + tenantSchema + "'");
            }

            UsageDayResponse today = UsageDayResponse.from(
                    rollupStore.snapshot(tenantSchema, app.getClientId(), PartnerUsageRollupStore.today()));

            List<UsageHistoryEntryResponse> history = em.createQuery(
                            "select d from PartnerRequestDaily d where d.partnerAppId = :appId "
                                    + "order by d.usageDate desc", PartnerRequestDaily.class)
                    .setParameter("appId", partnerAppId)
                    .setMaxResults(HISTORY_DAYS)
                    .getResultList()
                    .stream()
                    .map(UsageHistoryEntryResponse::from)
                    .toList();

            WebhookDeliverySummaryResponse webhookDeliveries = webhookSummary(em, partnerAppId);

            return new PortalUsageResponse(today, history, webhookDeliveries, errorRate(today));
        } finally {
            em.close();
            TenantContext.setTenantId(REGISTRY_TENANT_ID);
        }
    }

    private WebhookDeliverySummaryResponse webhookSummary(EntityManager em, UUID partnerAppId) {
        List<WebhookRegistration> registrations = em.createQuery(
                        "select w from WebhookRegistration w where w.partnerAppId = :appId "
                                + "and w.deletedAt is null", WebhookRegistration.class)
                .setParameter("appId", partnerAppId)
                .getResultList();

        int activeRegistrations = (int) registrations.stream().filter(WebhookRegistration::isActive).count();

        List<UUID> registrationIds = registrations.stream().map(WebhookRegistration::getId).toList();
        if (registrationIds.isEmpty()) {
            return new WebhookDeliverySummaryResponse(registrations.size(), activeRegistrations, 0, 0, 0, null);
        }

        long total = em.createQuery(
                        "select count(d) from WebhookDeliveryLog d where d.webhookRegistrationId in :ids", Long.class)
                .setParameter("ids", registrationIds)
                .getSingleResult();
        long success = em.createQuery(
                        "select count(d) from WebhookDeliveryLog d where d.webhookRegistrationId in :ids "
                                + "and d.success = true", Long.class)
                .setParameter("ids", registrationIds)
                .getSingleResult();
        Instant lastDeliveryAt = em.createQuery(
                        "select max(d.deliveredAt) from WebhookDeliveryLog d where d.webhookRegistrationId in :ids",
                        Instant.class)
                .setParameter("ids", registrationIds)
                .getSingleResult();

        return new WebhookDeliverySummaryResponse(
                registrations.size(), activeRegistrations, total, success, total - success, lastDeliveryAt);
    }

    private static double errorRate(UsageDayResponse today) {
        if (today.total() == 0) {
            return 0.0;
        }
        return (double) (today.clientError() + today.serverError()) / today.total();
    }
}
