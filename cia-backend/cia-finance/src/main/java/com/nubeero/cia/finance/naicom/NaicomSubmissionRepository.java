package com.nubeero.cia.finance.naicom;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NaicomSubmissionRepository extends JpaRepository<NaicomSubmission, UUID> {

    Optional<NaicomSubmission> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * Idempotency lookup — returns the live submission (if any) for the
     * given {@code (submission_type, period_id)} pair. Mirrors V41's
     * partial UNIQUE index {@code uq_naicom_submission_type_period}.
     */
    Optional<NaicomSubmission> findBySubmissionTypeAndPeriodIdAndDeletedAtIsNull(
            NaicomSubmissionType submissionType, UUID periodId);

    List<NaicomSubmission> findAllByPeriodIdAndDeletedAtIsNull(UUID periodId);

    List<NaicomSubmission> findAllByStateAndDeletedAtIsNull(NaicomSubmissionState state);

    /**
     * Submissions awaiting NAICOM acknowledgement — ordered by submission
     * time so the oldest pending appears first. Used by the slice-4.10
     * upload workflow to drive retries.
     */
    @Query("SELECT s FROM NaicomSubmission s " +
           "WHERE s.state = com.nubeero.cia.finance.naicom.NaicomSubmissionState.SUBMITTED " +
           "AND s.deletedAt IS NULL " +
           "ORDER BY s.submittedAt ASC")
    List<NaicomSubmission> findPendingAcknowledgement();
}
