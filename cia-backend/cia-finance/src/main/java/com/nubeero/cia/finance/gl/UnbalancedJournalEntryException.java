package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.BusinessRuleException;

import java.math.BigDecimal;

/**
 * Thrown by {@link JournalEntryService#post} when a candidate journal entry
 * fails the double-entry invariant: total debits must equal total credits.
 * Detection happens before any INSERT — the service computes the sums on
 * the request DTO and rejects the whole transaction (d6 — balance check at
 * service layer, not DB).
 *
 * <p>The exception message carries both totals (formatted to scale 2) and
 * the delta so the caller can see immediately which side is short. The
 * service does <em>not</em> attempt to auto-balance — it surfaces the bug
 * rather than mask it.
 *
 * <p>HTTP status: 422 Unprocessable Entity — the request is syntactically
 * valid but breaks the accounting invariant.
 */
public class UnbalancedJournalEntryException extends BusinessRuleException {

    public UnbalancedJournalEntryException(BigDecimal totalDebits, BigDecimal totalCredits) {
        super(
            "JOURNAL_ENTRY_UNBALANCED",
            String.format(
                "Journal entry is unbalanced: debits=%s credits=%s delta=%s",
                totalDebits.toPlainString(),
                totalCredits.toPlainString(),
                totalDebits.subtract(totalCredits).toPlainString()));
    }
}
