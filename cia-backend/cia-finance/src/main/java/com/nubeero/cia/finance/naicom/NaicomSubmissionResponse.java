package com.nubeero.cia.finance.naicom;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Wire DTO for {@link NaicomSubmission}. Includes the structured JSON
 * payload when fetched by id; callers paging through a list endpoint
 * may receive responses with a null {@code payload} field to keep
 * response sizes bounded (the engines emit deep nested maps).
 *
 * <p>Module 12 Phase 4 Slice 4.9.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NaicomSubmissionResponse(
    UUID id,
    NaicomSubmissionType submissionType,
    UUID periodId,
    LocalDate periodStart,
    LocalDate periodEnd,
    NaicomSubmissionState state,
    Instant submittedAt,
    String submittedBy,
    Instant acknowledgedAt,
    String acknowledgedBy,
    String naicomUid,
    Instant archivedAt,
    Instant retractedAt,
    String retractedBy,
    String retractionReason,
    String notes,
    Map<String, Object> payload
) {

    /** With payload — used on detail endpoints. */
    public static NaicomSubmissionResponse withPayload(NaicomSubmission s) {
        return new NaicomSubmissionResponse(
            s.getId(),
            s.getSubmissionType(),
            s.getPeriodId(),
            s.getPeriodStart(),
            s.getPeriodEnd(),
            s.getState(),
            s.getSubmittedAt(), s.getSubmittedBy(),
            s.getAcknowledgedAt(), s.getAcknowledgedBy(),
            s.getNaicomUid(),
            s.getArchivedAt(),
            s.getRetractedAt(), s.getRetractedBy(), s.getRetractionReason(),
            s.getNotes(),
            s.getPayload());
    }

    /** Without payload — used on list endpoints to bound response size. */
    public static NaicomSubmissionResponse summary(NaicomSubmission s) {
        return new NaicomSubmissionResponse(
            s.getId(),
            s.getSubmissionType(),
            s.getPeriodId(),
            s.getPeriodStart(),
            s.getPeriodEnd(),
            s.getState(),
            s.getSubmittedAt(), s.getSubmittedBy(),
            s.getAcknowledgedAt(), s.getAcknowledgedBy(),
            s.getNaicomUid(),
            s.getArchivedAt(),
            s.getRetractedAt(), s.getRetractedBy(), s.getRetractionReason(),
            s.getNotes(),
            null);
    }
}
