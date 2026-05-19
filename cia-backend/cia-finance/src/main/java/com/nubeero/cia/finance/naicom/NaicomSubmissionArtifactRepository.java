package com.nubeero.cia.finance.naicom;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NaicomSubmissionArtifactRepository extends JpaRepository<NaicomSubmissionArtifact, UUID> {

    Optional<NaicomSubmissionArtifact> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * Returns the live artifact (if any) for a given (submission, format)
     * pair. Mirrors V41's partial UNIQUE index
     * {@code uq_naicom_submission_artifact_format}.
     */
    Optional<NaicomSubmissionArtifact> findBySubmissionIdAndFormatAndDeletedAtIsNull(
            UUID submissionId, ArtifactFormat format);

    List<NaicomSubmissionArtifact> findAllBySubmissionIdAndDeletedAtIsNull(UUID submissionId);
}
