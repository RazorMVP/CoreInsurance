package com.nubeero.cia.finance.naicom;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NaicomSubmissionEventRepository extends JpaRepository<NaicomSubmissionEvent, UUID> {

    /**
     * State-transition history for a submission, oldest first. Auditors
     * traverse this list to reconstruct the submission's state-machine
     * path from initial DRAFT through to its current state.
     */
    List<NaicomSubmissionEvent> findAllBySubmissionIdOrderByOccurredAtAsc(UUID submissionId);
}
