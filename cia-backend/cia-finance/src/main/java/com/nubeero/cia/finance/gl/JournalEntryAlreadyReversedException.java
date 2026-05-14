package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.BusinessRuleException;

import java.util.UUID;

/**
 * Thrown by {@link JournalEntryService#reverse} when the target entry has
 * already been reversed (or is itself a reversal that's been reversed
 * again). D11 — single-reversal rule: an entry that is in status
 * {@code REVERSED} cannot be reversed again, and the system rejects
 * reversing a reversal because chained reversals destroy the audit chain.
 * If the user needs to "un-reverse", they post a fresh JE that mirrors the
 * reversal (i.e. matches the original).
 */
public class JournalEntryAlreadyReversedException extends BusinessRuleException {

    public JournalEntryAlreadyReversedException(UUID journalEntryId) {
        super(
            "JOURNAL_ENTRY_ALREADY_REVERSED",
            "Journal entry has already been reversed: " + journalEntryId);
    }
}
