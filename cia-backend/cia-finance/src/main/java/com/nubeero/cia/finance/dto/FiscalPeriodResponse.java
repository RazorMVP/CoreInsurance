package com.nubeero.cia.finance.dto;

import com.nubeero.cia.finance.gl.FiscalPeriodStatus;
import com.nubeero.cia.finance.gl.FiscalPeriodType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read model for one fiscal period. Returned by
 * {@code GET /api/v1/finance/fiscal-years/{id}/periods} and embedded in
 * {@link FiscalYearResponse} when the {@code includePeriods} query flag is
 * set.
 *
 * <p>{@code softClosedAt} / {@code hardClosedAt} are non-null only after
 * Slice 1.7's {@code PeriodLockService} flips them. In Slice 1.6 all
 * generated periods carry {@code OPEN} status and null close timestamps.
 */
public record FiscalPeriodResponse(
    UUID id,
    UUID fiscalYearId,
    FiscalPeriodType periodType,
    LocalDate startDate,
    LocalDate endDate,
    FiscalPeriodStatus status,
    Instant softClosedAt,
    Instant hardClosedAt
) {}
