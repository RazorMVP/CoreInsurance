package com.nubeero.cia.finance.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Wire contract for {@code POST /api/v1/finance/fiscal-years}.
 *
 * <p>All three fields are optional (D1=A — sensible defaults):
 * <ul>
 *   <li>{@code name}: if absent or blank, {@code FiscalYearService} derives
 *       {@code "FY" + startDate.getYear()} (d9).</li>
 *   <li>{@code startDate}: if absent, defaults to {@code LocalDate.of(currentYear, 1, 1)}
 *       (calendar year start). Must be the first day of a month if supplied.</li>
 *   <li>{@code endDate}: if absent, defaults to {@code startDate.plusYears(1).minusDays(1)}
 *       (exactly 12 months). Must be the last day of a month if supplied.</li>
 * </ul>
 *
 * <p>All-omitted form yields the current-calendar-year FY, which is the
 * tenant-bootstrap default (D4=A). The same request validates strictly when
 * fields are supplied — see {@code InvalidFiscalYearBoundsException}.
 */
public record CreateFiscalYearRequest(
    @Size(max = 50) String name,
    LocalDate startDate,
    LocalDate endDate
) {}
