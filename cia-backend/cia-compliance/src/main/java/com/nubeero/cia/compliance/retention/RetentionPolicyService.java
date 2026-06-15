package com.nubeero.cia.compliance.retention;

import com.nubeero.cia.common.exception.BusinessRuleException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads + updates the per-tenant {@link DataRetentionPolicy} singleton, with schedule validation. */
@Service
@RequiredArgsConstructor
public class RetentionPolicyService {

    private static final Set<String> FREQUENCIES = Set.of("WEEKLY", "MONTHLY");

    private final DataRetentionPolicyRepository repository;

    /** Returns the tenant's policy, lazily creating it with defaults on first access. */
    @Transactional
    public DataRetentionPolicy getOrCreate() {
        return repository.findFirstByDeletedAtIsNull()
                .orElseGet(() -> repository.save(new DataRetentionPolicy()));
    }

    /** Validates + applies an update. Throws {@link BusinessRuleException} on bad input. */
    @Transactional
    public DataRetentionPolicy update(RetentionPolicyRequest req) {
        validate(req);
        DataRetentionPolicy p = getOrCreate();
        p.setCustomerPiiRetentionDays(req.customerPiiRetentionDays());
        p.setPurgeEnabled(req.purgeEnabled());
        p.setPurgeFrequency(req.purgeFrequency());
        p.setPurgeDayOfWeek(req.purgeDayOfWeek());
        p.setPurgeHourUtc(req.purgeHourUtc());
        return repository.save(p);
    }

    /** Pure validation — package-visible so it is unit-testable without a repository. */
    void validate(RetentionPolicyRequest req) {
        if (req.customerPiiRetentionDays() <= 0) {
            throw new BusinessRuleException("INVALID_RETENTION_DAYS", "retention days must be > 0");
        }
        if (req.purgeFrequency() == null || !FREQUENCIES.contains(req.purgeFrequency())) {
            throw new BusinessRuleException("INVALID_PURGE_FREQUENCY",
                    "purge frequency must be one of " + FREQUENCIES);
        }
        if (req.purgeDayOfWeek() < 0 || req.purgeDayOfWeek() > 6) {
            throw new BusinessRuleException("INVALID_PURGE_DAY", "purge day of week must be 0..6");
        }
        if (req.purgeHourUtc() < 0 || req.purgeHourUtc() > 23) {
            throw new BusinessRuleException("INVALID_PURGE_HOUR", "purge hour must be 0..23");
        }
    }
}
