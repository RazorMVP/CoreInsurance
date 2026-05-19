package com.nubeero.cia.finance.naicom;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * Thrown when an artifact lookup by {@code (submissionId, format)} pair
 * has no live (non-deleted) row in {@code naicom_submission_artifact}.
 * Surfaced as HTTP 404.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ArtifactNotFoundException extends RuntimeException {

    public ArtifactNotFoundException(UUID submissionId, ArtifactFormat format) {
        super("No live artifact for submission " + submissionId
            + " with format " + format);
    }
}
