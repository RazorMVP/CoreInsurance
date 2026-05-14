package com.nubeero.cia.finance.gl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Lookup helper that maps a business date to the enclosing fiscal period id.
 *
 * <p>Slice 1.4 (gateway) — per design decision D1=A, every
 * {@link JournalEntry} is anchored to a MONTH period. The {@code period_id}
 * FK is non-null at the DB level; this service is the only legitimate path
 * to populate it from a {@code business_date}.
 *
 * <p>Why a dedicated helper rather than a static utility: Hibernate's
 * tenant-aware routing only fires through a real {@code @Repository} bean,
 * and the FK lookup needs to be a query (not a hardcoded mapping). Keeping
 * resolution in one place also makes Slice 1.7's grace-window enforcement
 * additive — that slice extends this resolver with a status check rather
 * than reaching across modules.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FiscalPeriodResolver {

    private final FiscalPeriodRepository repository;

    /**
     * Returns the {@link FiscalPeriod} of type {@code MONTH} whose date range
     * contains {@code businessDate} (inclusive). Throws
     * {@link FiscalPeriodNotFoundException} if no MONTH period covers that
     * date — almost always because the tenant hasn't activated a fiscal year
     * spanning the date.
     */
    public FiscalPeriod resolveMonthForBusinessDate(LocalDate businessDate) {
        return repository
            .findFirstByPeriodTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNull(
                FiscalPeriodType.MONTH, businessDate, businessDate)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(businessDate, FiscalPeriodType.MONTH));
    }

    /**
     * Convenience overload returning just the id, since
     * {@link JournalEntryService#post(java.time.LocalDate, java.time.LocalDate,
     * String, String, String, String, java.util.List)} only needs the id to
     * populate {@code journal_entry.period_id}. Saves a downstream
     * {@code .getId()} call.
     */
    public UUID resolveMonthIdForBusinessDate(LocalDate businessDate) {
        return resolveMonthForBusinessDate(businessDate).getId();
    }
}
