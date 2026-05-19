package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Thrown when a journal entry referenced by id (for reversal or read) does
 * not exist or is soft-deleted. 404 is appropriate here — the caller
 * addressed a missing resource.
 */
public class JournalEntryNotFoundException extends CiaException {

    public JournalEntryNotFoundException(UUID id) {
        super(
            "JOURNAL_ENTRY_NOT_FOUND",
            "Journal entry not found: " + id,
            HttpStatus.NOT_FOUND);
    }
}
