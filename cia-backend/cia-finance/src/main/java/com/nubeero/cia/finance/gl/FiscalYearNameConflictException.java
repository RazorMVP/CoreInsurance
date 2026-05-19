package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link FiscalYearService#create} when the requested
 * {@code name} (either supplied explicitly or derived as
 * {@code "FY" + startDate.getYear()}) already exists for this tenant.
 *
 * <p>HTTP status: 409 Conflict — typical pattern for "uniqueness violation
 * at creation time". The DB UNIQUE constraint is the final word; the
 * service does an advisory read so the surface error is consistent and
 * not a wrapped {@code DataIntegrityViolationException}.
 */
public class FiscalYearNameConflictException extends CiaException {

    public FiscalYearNameConflictException(String name) {
        super(
            "FISCAL_YEAR_NAME_CONFLICT",
            "Fiscal year with name '" + name + "' already exists for this tenant.",
            HttpStatus.CONFLICT);
    }
}
