package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Thrown by {@link FiscalYearService} when a caller addresses a fiscal year
 * by id (or by "the active FY" lookup that finds nothing).
 *
 * <p>HTTP status: 404 Not Found. The error code distinguishes between
 * "addressed by id" and "no active FY exists" so frontends and the tenant
 * bootstrap workflow can branch correctly.
 */
public class FiscalYearNotFoundException extends CiaException {

    public FiscalYearNotFoundException(UUID id) {
        super("FISCAL_YEAR_NOT_FOUND", "Fiscal year not found: " + id, HttpStatus.NOT_FOUND);
    }

    public static FiscalYearNotFoundException noActiveYear() {
        return new FiscalYearNotFoundException("FISCAL_YEAR_NO_ACTIVE",
            "No fiscal year is currently ACTIVE for this tenant. Create + activate one before posting.");
    }

    private FiscalYearNotFoundException(String code, String message) {
        super(code, message, HttpStatus.NOT_FOUND);
    }
}
