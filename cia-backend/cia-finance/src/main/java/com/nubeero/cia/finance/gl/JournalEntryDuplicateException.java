package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link JournalEntryService#post} when the
 * {@code (sourceModule, sourceEventType, sourceReference)} idempotency key
 * matches an existing journal entry. The service does an advisory read
 * before the INSERT to map the DB UNIQUE conflict to a clean 409 with the
 * existing entry id — easier for Slice 1.5 sub-ledger listeners to handle
 * than a wrapped {@code DataIntegrityViolationException}.
 *
 * <p>HTTP status: 409 Conflict — caller should not retry; the entry is
 * already in the GL.
 */
public class JournalEntryDuplicateException extends CiaException {

    public JournalEntryDuplicateException(String sourceModule, String sourceEventType, String sourceReference) {
        super(
            "JOURNAL_ENTRY_DUPLICATE",
            "Journal entry already exists for " + sourceModule + "/" + sourceEventType + "/" + sourceReference,
            HttpStatus.CONFLICT);
    }
}
