package com.nubeero.cia.finance.naicom;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a state-machine transition is attempted from a state the
 * V41 graph does not permit (e.g. {@code submit()} on an already
 * SUBMITTED row, or {@code archive()} on a DRAFT). Surfaced as HTTP 409.
 *
 * <p>The CHECK constraints in V41 enforce field-presence invariants
 * per-state (e.g. SUBMITTED ⇒ submitted_at NOT NULL); this exception
 * is for the orthogonal transition-graph rule that lives in the
 * service layer.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class IllegalSubmissionStateException extends RuntimeException {

    public IllegalSubmissionStateException(NaicomSubmissionState from,
                                            NaicomSubmissionState attempted,
                                            String operation) {
        super("Illegal submission state transition: cannot " + operation
            + " a submission in state " + from
            + " (target " + attempted + ")");
    }
}
