package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Thrown by {@link FiscalYearService#delete} when the target FY (or any of
 * its child periods) is referenced by at least one {@code journal_entry}
 * row. The GL is immutable history — a FY with postings cannot be wiped.
 *
 * <p>HTTP status: 422. Frontends should surface this with a "this fiscal
 * year has financial activity; close it instead of deleting" message.
 */
public class FiscalYearHasJournalEntriesException extends CiaException {

    public FiscalYearHasJournalEntriesException(UUID fiscalYearId, long journalEntryCount) {
        super(
            "FISCAL_YEAR_HAS_JOURNAL_ENTRIES",
            "Fiscal year " + fiscalYearId + " cannot be deleted: " + journalEntryCount +
                " journal entr" + (journalEntryCount == 1 ? "y" : "ies") +
                " reference its periods. Close the fiscal year instead.",
            HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
