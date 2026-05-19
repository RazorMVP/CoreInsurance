package com.nubeero.cia.finance.naicom;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire DTO for one row of the submission state-transition history.
 * Module 12 Phase 4 Slice 4.9.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NaicomSubmissionEventResponse(
    UUID id,
    UUID submissionId,
    NaicomSubmissionState fromState,
    NaicomSubmissionState toState,
    String reason,
    String actor,
    Instant occurredAt
) {

    public static NaicomSubmissionEventResponse from(NaicomSubmissionEvent e) {
        return new NaicomSubmissionEventResponse(
            e.getId(),
            e.getSubmissionId(),
            e.getFromState(),
            e.getToState(),
            e.getReason(),
            e.getActor(),
            e.getOccurredAt());
    }
}
