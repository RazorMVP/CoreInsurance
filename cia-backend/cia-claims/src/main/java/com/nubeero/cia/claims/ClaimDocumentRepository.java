package com.nubeero.cia.claims;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimDocumentRepository extends JpaRepository<ClaimDocument, UUID> {

    Optional<ClaimDocument> findByIdAndDeletedAtIsNull(UUID id);

    Page<ClaimDocument> findAllByClaim_IdAndDeletedAtIsNull(UUID claimId, Pageable pageable);

    Page<ClaimDocument> findAllByClaim_IdAndDocumentTypeAndDeletedAtIsNull(
            UUID claimId, ClaimDocumentType documentType, Pageable pageable);

    List<ClaimDocument> findAllByClaim_IdAndDocumentTypeAndDeletedAtIsNull(
            UUID claimId, ClaimDocumentType documentType);
}
