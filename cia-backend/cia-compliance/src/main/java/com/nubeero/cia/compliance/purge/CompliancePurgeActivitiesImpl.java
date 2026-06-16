package com.nubeero.cia.compliance.purge;

import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.compliance.retention.DataRetentionPolicy;
import com.nubeero.cia.compliance.retention.RetentionPolicyService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
@RequiredArgsConstructor
public class CompliancePurgeActivitiesImpl implements CompliancePurgeActivities {

    private final EntityManager em;
    private final RetentionPolicyService policyService;
    private final CustomerPurgeRepository purgeRepo;
    private final CustomerPiiPurgeService purgeService;

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<String> listActiveTenants() {
        // No tenant context here ⇒ resolver returns "public" ⇒ the registry lives there.
        Query q = em.createNativeQuery(
            "SELECT schema_name FROM public.tenants WHERE active = TRUE ORDER BY schema_name");
        return ((List<?>) q.getResultList()).stream().map(Object::toString).toList();
    }

    @Override
    public PurgeTenantResult purgeTenant(String schema) {
        return purgeTenantAt(schema, Instant.now());
    }

    /**
     * Public test seam — same logic with an injectable clock. PUBLIC because the IT lives in a different
     * package. All DB writes go through OTHER beans (purgeRepo, purgeService) so their @Transactional
     * proxies apply — a self-invoked @Transactional on this Temporal-invoked bean would be a no-op.
     */
    public PurgeTenantResult purgeTenantAt(String schema, Instant now) {
        TenantContext.setTenantId(schema);   // interceptor clears it in finally
        DataRetentionPolicy policy = policyService.getOrCreate();
        if (!policy.isPurgeEnabled()) {
            return PurgeTenantResult.skipped(schema, "purge_disabled");
        }
        if (!PurgeWindow.matches(now, policy.getPurgeFrequency(),
                policy.getPurgeDayOfWeek(), policy.getPurgeHourUtc())) {
            return PurgeTenantResult.skipped(schema, "window_no_match");
        }
        if (!PurgeWindow.debouncePassed(now, policy.getLastPurgeRunAt())) {
            return PurgeTenantResult.skipped(schema, "debounced");
        }
        purgeRepo.stampLastPurgeRun(now);   // claim the window BEFORE purging (cross-bean → @Transactional applies)

        int purged = 0;
        for (UUID customerId : purgeRepo.findEligibleCustomerIds(policy.getCustomerPiiRetentionDays())) {
            try {
                if (purgeService.purgeCustomer(schema, customerId, policy.getCustomerPiiRetentionDays())) {
                    purged++;
                }
            } catch (RuntimeException ex) {
                log.warn("PII purge: customer {} in tenant {} failed (skipping): {}",
                        customerId, schema, ex.getMessage());
            }
        }
        return new PurgeTenantResult(schema, true, purged, null);
    }
}
