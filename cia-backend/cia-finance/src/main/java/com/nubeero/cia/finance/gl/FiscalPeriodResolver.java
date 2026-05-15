package com.nubeero.cia.finance.gl;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * <p>Slice 1.6 (d10) extends the resolver with lazy DAY-period generation:
 * {@link #resolveDayForBusinessDate(LocalDate)} returns the existing DAY
 * row if one is present, or creates one on the fly within the enclosing
 * fiscal year. MONTH / QUARTER / HALF_YEAR / YEAR are generated eagerly at
 * fiscal-year create time (D2=A); DAY remains lazy to avoid the
 * 365-row-per-FY clutter that virtually no tenant will need in full.
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

    private final FiscalPeriodRepository periodRepository;
    private final FiscalYearRepository fiscalYearRepository;

    /**
     * Returns the {@link FiscalPeriod} of type {@code MONTH} whose date range
     * contains {@code businessDate} (inclusive). Throws
     * {@link FiscalPeriodNotFoundException} if no MONTH period covers that
     * date — almost always because the tenant hasn't activated a fiscal year
     * spanning the date.
     */
    public FiscalPeriod resolveMonthForBusinessDate(LocalDate businessDate) {
        return periodRepository
            .findFirstByPeriodTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNull(
                FiscalPeriodType.MONTH, businessDate, businessDate)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(businessDate, FiscalPeriodType.MONTH));
    }

    /**
     * Convenience overload returning just the id, since
     * {@code JournalEntryService.post} only needs the id to populate
     * {@code journal_entry.period_id}. Saves a downstream {@code .getId()}
     * call.
     */
    public UUID resolveMonthIdForBusinessDate(LocalDate businessDate) {
        return resolveMonthForBusinessDate(businessDate).getId();
    }

    /**
     * Returns the DAY period covering {@code businessDate}, creating it
     * lazily if it doesn't yet exist. The new row is anchored to the
     * fiscal year whose range encloses the date; if no FY covers the date,
     * throws {@link FiscalPeriodNotFoundException} — there's no parent to
     * attach the new DAY row to.
     *
     * <p>The method is {@code @Transactional} (read-write) so the INSERT
     * persists even when the caller's outer scope is read-only. Concurrent
     * callers requesting the same date may race; the DB
     * {@code uq_fiscal_period_year_type_start} UNIQUE constraint catches
     * the second writer, which then re-reads and returns the winner. We
     * accept the rare retry over taking a row-level lock for the
     * common-case fast path.
     */
    @Transactional
    public FiscalPeriod resolveDayForBusinessDate(LocalDate businessDate) {
        return periodRepository
            .findFirstByPeriodTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNull(
                FiscalPeriodType.DAY, businessDate, businessDate)
            .orElseGet(() -> generateDayPeriod(businessDate));
    }

    private FiscalPeriod generateDayPeriod(LocalDate date) {
        FiscalYear fy = fiscalYearRepository.findEnclosing(date)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(date, FiscalPeriodType.DAY));
        FiscalPeriod period = new FiscalPeriod();
        period.setFiscalYearId(fy.getId());
        period.setPeriodType(FiscalPeriodType.DAY);
        period.setStartDate(date);
        period.setEndDate(date);
        period.setStatus(FiscalPeriodStatus.OPEN);
        period.setCreatedBy(currentUser());
        return periodRepository.save(period);
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
