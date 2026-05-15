package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.BusinessRuleException;

import java.time.LocalDate;

/**
 * Thrown by {@link FiscalYearService#create} when the supplied
 * {@code startDate} / {@code endDate} fail the Slice 1.6 application-layer
 * invariants:
 *
 * <ul>
 *   <li>{@code startDate} must be the first day of its month
 *       (period-generation math assumes month-aligned boundaries).</li>
 *   <li>{@code endDate} must be the last day of its month.</li>
 *   <li>{@code endDate} must be exactly 12 calendar months minus one day
 *       after {@code startDate}. Partial / multi-year FYs are out of scope
 *       for this slice — they require dedicated period-generation paths.</li>
 * </ul>
 *
 * <p>HTTP status: 422 (via {@link BusinessRuleException}).
 */
public class InvalidFiscalYearBoundsException extends BusinessRuleException {

    public InvalidFiscalYearBoundsException(String reason, LocalDate startDate, LocalDate endDate) {
        super(
            "INVALID_FISCAL_YEAR_BOUNDS",
            "Invalid fiscal year bounds [" + startDate + " .. " + endDate + "]: " + reason);
    }
}
