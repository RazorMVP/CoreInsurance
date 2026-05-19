package com.nubeero.cia.finance.gl;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link FiscalPeriod}. Slice 1.4 (gateway)
 * used only the date-range MONTH lookup. Slice 1.6 adds the listing /
 * containment finders {@code FiscalYearService} needs for the
 * {@code GET /fiscal-years/{id}/periods} endpoint and the
 * delete-blocked-by-JE invariant check.
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
     * starting on the same date inside one fiscal year — and the
     * generation flow (Slice 1.6) lays out non-overlapping months — so at
     * most one row can match a given (type, date) tuple.
     */
    Optional<FiscalPeriod> findFirstByPeriodTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNull(
        FiscalPeriodType periodType, LocalDate startBound, LocalDate endBound);

    /**
     * All periods (every {@link FiscalPeriodType}) belonging to a fiscal year,
     * ordered by start date then period type ordinal so MONTH rows for a given
     * month appear together with their enclosing QUARTER / HALF_YEAR / YEAR
     * rows in a predictable layout.
     */
    List<FiscalPeriod> findByFiscalYearIdAndDeletedAtIsNullOrderByStartDateAscPeriodTypeAsc(UUID fiscalYearId);

    /**
     * IDs only — used by {@code FiscalYearService.delete} to feed the
     * journal-entry FK-count check in one round-trip without loading the
     * full {@link FiscalPeriod} graph.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT p.id FROM FiscalPeriod p WHERE p.fiscalYearId = :fiscalYearId AND p.deletedAt IS NULL")
    List<UUID> findIdsByFiscalYearId(@org.springframework.data.repository.query.Param("fiscalYearId") UUID fiscalYearId);
}
