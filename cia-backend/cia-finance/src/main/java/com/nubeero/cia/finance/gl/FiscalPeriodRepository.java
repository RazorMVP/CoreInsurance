package com.nubeero.cia.finance.gl;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link FiscalPeriod}. Slice 1.4 (gateway) uses
 * only the date-range MONTH lookup; lifecycle finders (find by status, find
 * by fiscal year + period_type) are added in Slice 1.6 alongside
 * {@code FiscalYearService}.
 */
public interface FiscalPeriodRepository extends JpaRepository<FiscalPeriod, UUID> {

    /**
     * Locates the active fiscal period of the given type whose date range
     * covers {@code businessDate} (inclusive on both sides). Soft-deleted rows
     * are excluded.
     *
     * <p>Used by {@link FiscalPeriodResolver#resolveMonthForBusinessDate}. The
     * underlying schema has a uniqueness invariant
     * {@code uq_fiscal_period_year_type_start} preventing two MONTH rows from
     * starting on the same date inside one fiscal year — and the activation
     * flow (Slice 1.6) generates non-overlapping months — so at most one row
     * can match a given (type, date) tuple.
     */
    Optional<FiscalPeriod> findFirstByPeriodTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNull(
        FiscalPeriodType periodType, LocalDate startBound, LocalDate endBound);
}
