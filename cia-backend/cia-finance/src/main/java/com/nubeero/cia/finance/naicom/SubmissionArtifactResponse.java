package com.nubeero.cia.finance.naicom;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire DTO for a {@link NaicomSubmissionArtifact}. The raw artifact
 * bytes are streamed via the {@code /download} endpoint; this DTO is
 * for metadata listing.
 *
 * <p>Module 12 Phase 4 Slice 4.10.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmissionArtifactResponse(
    UUID id,
    UUID submissionId,
    ArtifactFormat format,
    String storagePath,
    long sizeBytes,
    String sha256Hex,
    Instant renderedAt,
    String renderedBy
) {

    public static SubmissionArtifactResponse from(NaicomSubmissionArtifact a) {
        return new SubmissionArtifactResponse(
            a.getId(),
            a.getSubmissionId(),
            a.getFormat(),
            a.getStoragePath(),
            a.getSizeBytes(),
            a.getSha256Hex(),
            a.getRenderedAt(),
            a.getRenderedBy());
    }
}
