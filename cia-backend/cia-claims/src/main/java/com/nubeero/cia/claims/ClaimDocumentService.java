package com.nubeero.cia.claims;

import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClaimDocumentService {

    private final ClaimDocumentRepository documentRepository;
    private final ClaimRepository claimRepository;

    public Page<ClaimDocument> findByClaimId(UUID claimId, Pageable pageable) {
        return documentRepository.findAllByClaim_IdAndDeletedAtIsNull(claimId, pageable);
    }

    public ClaimDocument findOrThrow(UUID id) {
        return documentRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("ClaimDocument", id));
    }

    @Transactional
    public ClaimDocument upload(UUID claimId, ClaimDocumentType documentType,
                                 String fileName, String filePath, Long fileSize) {
        Claim claim = claimRepository.findByIdAndDeletedAtIsNull(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Claim", claimId));

        if (claim.getStatus() == ClaimStatus.WITHDRAWN
                || claim.getStatus() == ClaimStatus.REJECTED) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Cannot upload documents to a " + claim.getStatus() + " claim");
        }

        ClaimDocument doc = ClaimDocument.builder()
                .claim(claim)
                .documentType(documentType)
                .fileName(fileName)
                .filePath(filePath)
                .fileSize(fileSize)
                .uploadedBy(currentUser())
                .build();

        return documentRepository.save(doc);
    }

    @Transactional
    public void delete(UUID claimId, UUID documentId) {
        ClaimDocument doc = findOrThrow(documentId);
        if (!doc.getClaim().getId().equals(claimId)) {
            throw new BusinessRuleException("DOCUMENT_NOT_FOUND",
                    "Document does not belong to claim " + claimId);
        }
        doc.softDelete();
        documentRepository.save(doc);
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
