package com.nubeero.cia.finance.gl;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link FiscalYear}. Slice 1.6 exposes the
 * minimal finder surface {@code FiscalYearService} needs:
 *
 * <ul>
 *   <li>{@link #findByIdAndDeletedAtIsNull(UUID)} — primary key load with
 *       soft-delete guard.</li>
 *   <li>{@link #findByNameAndDeletedAtIsNull(String)} — uniqueness check
 *       before INSERT; the DB UNIQUE constraint is the final word, but the
 *       service does an advisory read so duplicate-name attempts surface
 *       as a clean {@code FiscalYearNameConflictException} (422) rather
 *       than a wrapped {@code DataIntegrityViolationException}.</li>
 *   <li>{@link #findByStatusAndDeletedAtIsNull(FiscalYearStatus)} — used by
 *       activation flow (D3=B) to detect a sibling {@code ACTIVE} year and
 *       by callers fetching the current FY.</li>
 *   <li>{@link #findEnclosing(LocalDate)} — the date-range lookup used by
 *       {@code FiscalPeriodResolver}'s lazy DAY-period generation (d10).</li>
 *   <li>{@link #findAllByDeletedAtIsNullOrderByStartDateDesc()} — list
 *       endpoint, newest first.</li>
 * </ul>
 */
public interface FiscalYearRepository extends JpaRepository<FiscalYear, UUID> {

    Optional<FiscalYear> findByIdAndDeletedAtIsNull(UUID id);

    Optional<FiscalYear> findByNameAndDeletedAtIsNull(String name);

    List<FiscalYear> findByStatusAndDeletedAtIsNull(FiscalYearStatus status);

    Optional<FiscalYear> findFirstByStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNull(
        LocalDate startBound, LocalDate endBound);

    List<FiscalYear> findAllByDeletedAtIsNullOrderByStartDateDesc();

    /**
     * Convenience overload mirroring {@code FiscalPeriodResolver}'s naming.
     * Returns the (at most one) FY whose date range contains {@code date}.
     * Useful for the lazy DAY-period generation flow — the resolver asks
     * "does any FY cover this date?" before creating a new period row.
     */
    default Optional<FiscalYear> findEnclosing(LocalDate date) {
        return findFirstByStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNull(date, date);
    }
}
