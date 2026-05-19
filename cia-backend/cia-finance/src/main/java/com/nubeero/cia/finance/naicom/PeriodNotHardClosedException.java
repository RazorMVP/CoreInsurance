package com.nubeero.cia.finance.naicom;

import com.nubeero.cia.finance.gl.FiscalPeriodStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * Thrown when submission generation is attempted against a fiscal period
 * whose status is not {@code HARD_CLOSED}. Phase 4 submissions are
 * read-side aggregates over already-frozen ledger state — running them
 * against an OPEN or SOFT_CLOSED period would surface figures the
 * regulator would later see change. Surfaced as HTTP 422.
 *
 * <p>The hard-close precondition lives in {@link NaicomSubmissionService},
 * not the DB, because the canonical period-state authority is
 * {@code period_lock} (V31 Type-2 SCD) and reading the latest row
 * requires a subquery that obscures the business rule.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class PeriodNotHardClosedException extends RuntimeException {

    public PeriodNotHardClosedException(UUID periodId, FiscalPeriodStatus actual) {
        super("Fiscal period " + periodId + " is " + actual
            + "; NAICOM submission generation requires HARD_CLOSED.");
    }
}
