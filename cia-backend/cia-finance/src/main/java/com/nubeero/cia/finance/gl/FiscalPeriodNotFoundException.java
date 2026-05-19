package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Thrown when a fiscal period lookup fails. Two flavours of failure are
 * surfaced by the same exception:
 * <ul>
 *   <li>By date — {@link FiscalPeriodResolver} can't find a period of the
 *       requested type covering a given business date. Maps to
 *       422 Unprocessable Entity — the system has no period to assign the
 *       request to.</li>
 *   <li>By id — a caller addresses a specific period UUID that isn't in
 *       the database. Maps to 404 Not Found — standard "addressed
 *       resource missing" semantics.</li>
 * </ul>
 */
public class FiscalPeriodNotFoundException extends CiaException {

    public FiscalPeriodNotFoundException(LocalDate businessDate, FiscalPeriodType periodType) {
        super(
            "FISCAL_PERIOD_NOT_FOUND",
            "No active " + periodType + " fiscal period covers business date " + businessDate +
                ". Verify a fiscal year is active for this tenant.",
            HttpStatus.UNPROCESSABLE_ENTITY);
    }

    public FiscalPeriodNotFoundException(UUID periodId) {
        super(
            "FISCAL_PERIOD_NOT_FOUND",
            "Fiscal period " + periodId + " not found.",
            HttpStatus.NOT_FOUND);
    }
}
