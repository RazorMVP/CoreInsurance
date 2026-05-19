package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Thrown by {@link FiscalYearService#activate} when another fiscal year
 * already holds {@link FiscalYearStatus#ACTIVE} for this tenant.
 *
 * <p>D3=B locks the explicit-close model: admin must {@code close()} the
 * prior FY before activating a successor. This trades the V31 comment's
 * "atomic deactivate sibling" convenience for clearer audit semantics —
 * {@code CLOSED} in V31's three-state enum means "the year is done", not
 * just "no longer current", so forcing prior → CLOSED mid-year would
 * conflate two distinct lifecycle events.
 *
 * <p>HTTP status: 422 Unprocessable Entity — the request is well-formed
 * but conflicts with current tenant state.
 */
public class FiscalYearActivationConflictException extends CiaException {

    public FiscalYearActivationConflictException(UUID currentActiveId, String currentActiveName) {
        super(
            "FISCAL_YEAR_ACTIVATION_CONFLICT",
            "Cannot activate fiscal year: " + currentActiveName + " (" + currentActiveId +
                ") is already ACTIVE. Close it first before activating a successor.",
            HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
