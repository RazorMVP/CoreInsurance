package com.nubeero.cia.finance.naicom;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * Thrown when payload re-generation is attempted on a submission whose
 * state is past DRAFT. Once SUBMITTED, the payload is the canonical
 * regulator-bound record — re-running the source engine would silently
 * mutate auditor-visible figures, so the service rejects it and the
 * caller must {@code RETRACT} + generate a fresh submission.
 * Surfaced as HTTP 409.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class PayloadFrozenException extends RuntimeException {

    public PayloadFrozenException(UUID submissionId, NaicomSubmissionState state) {
        super("Submission " + submissionId + " is " + state
            + "; payload is frozen. Retract to make changes, "
            + "then generate a fresh submission.");
    }
}
