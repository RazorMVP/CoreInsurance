package com.nubeero.cia.finance.naicom;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * Thrown when a submission id has no live (non-deleted) row in
 * {@code naicom_submission}. Surfaced as HTTP 404.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NaicomSubmissionNotFoundException extends RuntimeException {

    public NaicomSubmissionNotFoundException(UUID submissionId) {
        super("NAICOM submission not found: " + submissionId);
    }
}
