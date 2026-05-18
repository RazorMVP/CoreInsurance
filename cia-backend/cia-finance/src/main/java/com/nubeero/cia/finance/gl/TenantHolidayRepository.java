package com.nubeero.cia.finance.gl;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Reads-only repository — Slice 1.7c never mutates tenant_holiday from
 * application code; it's seeded out-of-band per tenant (admin UI lives in
 * a future Phase 6 slice).
 */
public interface TenantHolidayRepository extends JpaRepository<TenantHoliday, UUID> {

    /** All active (non-soft-deleted) holidays, used by addBusinessDays. */
    List<TenantHoliday> findAllByDeletedAtIsNullOrderByHolidayDateAsc();

    /** Existence check used by tests and the future admin UI. */
    boolean existsByHolidayDateAndDeletedAtIsNull(LocalDate holidayDate);
}
