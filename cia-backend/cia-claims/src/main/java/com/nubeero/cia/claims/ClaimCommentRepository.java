package com.nubeero.cia.claims;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ClaimCommentRepository extends JpaRepository<ClaimComment, UUID> {

    /** Newest-first paged feed for a claim. */
    Page<ClaimComment> findAllByClaim_IdAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID claimId, Pageable pageable);
}
