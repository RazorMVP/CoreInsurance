package com.nubeero.cia.partner.usage;

import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.partner.app.PartnerApp;
import com.nubeero.cia.partner.app.PartnerAppRepository;
import com.nubeero.cia.partner.usage.PartnerUsageRollupStore.DailyCounts;
import com.nubeero.cia.partner.usage.PartnerUsageRollupStore.RollupKey;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Drains one day's {@link PartnerUsageRollupStore} counters into the durable {@link
 * PartnerRequestDaily} table — the business logic behind {@link PartnerUsageFlushActivitiesImpl}'s
 * daily cron activity.
 *
 * <h2>No tenant registry sweep needed</h2>
 * Unlike most cross-tenant Temporal jobs, this one does NOT need to enumerate {@code
 * public.tenants} — {@link PartnerUsageRollupStore#keysForDate} already returns every {@code
 * (tenantId, clientId)} pair that recorded LIVE traffic that day (the filter only ever writes a
 * key when a real request comes in), so tenants with zero partner-API traffic are correctly
 * skipped for free.
 *
 * <h2>Tenant switching outside an HTTP request</h2>
 * This runs from a Temporal activity thread, not inside an HTTP request — there is no OSIV-bound
 * {@code EntityManager} pinning the tenant identifier for the whole call (that pitfall is specific
 * to {@code spring.jpa.open-in-view}'s request-scoped session, see {@code
 * TenantScopedPartnerAppReader}'s javadoc). Each Spring Data repository call here opens its own
 * transaction/session on demand, so switching {@link TenantContext} immediately before each
 * per-key repository call is sufficient — no raw {@code EntityManagerFactory} needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerUsageRollupFlushService {

    private static final String REGISTRY_TENANT_ID = "public";

    private final PartnerUsageRollupStore rollupStore;
    private final PartnerAppRepository partnerAppRepository;
    private final PartnerRequestDailyRepository dailyRepository;

    /** @return how many {@code (tenant, clientId)} keys were successfully upserted. */
    public int flushDate(LocalDate date) {
        int flushed = 0;
        for (RollupKey key : rollupStore.keysForDate(date)) {
            if (flushOne(key, date)) {
                flushed++;
            }
        }
        return flushed;
    }

    private boolean flushOne(RollupKey key, LocalDate date) {
        TenantContext.setTenantId(key.tenantId());
        try {
            return upsert(key, date);
        } catch (RuntimeException e) {
            log.warn("PartnerUsageFlush: failed to flush tenant='{}' clientId='{}' date={}: {}",
                    key.tenantId(), key.clientId(), date, e.getMessage());
            return false;
        } finally {
            TenantContext.setTenantId(REGISTRY_TENANT_ID);
        }
    }

    private boolean upsert(RollupKey key, LocalDate date) {
        PartnerApp app = partnerAppRepository.findByClientId(key.clientId()).orElse(null);
        if (app == null) {
            log.warn("PartnerUsageFlush: no PartnerApp for clientId='{}' in tenant '{}' — skipping",
                    key.clientId(), key.tenantId());
            return false;
        }

        DailyCounts counts = rollupStore.snapshot(key.tenantId(), key.clientId(), date);
        PartnerRequestDaily row = dailyRepository.findByPartnerAppIdAndUsageDate(app.getId(), date)
                .orElseGet(PartnerRequestDaily::new);
        row.setPartnerAppId(app.getId());
        row.setUsageDate(date);
        row.setTotal(counts.total());
        row.setSuccess(counts.success());
        row.setClientError(counts.clientError());
        row.setServerError(counts.serverError());
        dailyRepository.save(row);
        return true;
    }
}
