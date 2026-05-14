package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;

/**
 * Thrown by {@link FiscalPeriodResolver} when no fiscal period of the
 * requested type covers a given business date. Almost always means the
 * tenant has not yet activated a fiscal year whose range includes that date
 * (Slice 1.6 — fiscal year activation generates the underlying period rows).
 *
 * <p>HTTP status: 422 Unprocessable Entity — the request is well-formed, but
 * the system has no fiscal period to assign it to. 404 would imply a
 * missing resource the caller addressed by id; here the caller specifies a
 * business date and the system fails to find an enclosing period.
 */
public class FiscalPeriodNotFoundException extends CiaException {

    public FiscalPeriodNotFoundException(LocalDate businessDate, FiscalPeriodType periodType) {
        super(
            "FISCAL_PERIOD_NOT_FOUND",
            "No active " + periodType + " fiscal period covers business date " + businessDate +
                ". Verify a fiscal year is active for this tenant.",
            HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
