package com.nubeero.cia.claims;

import com.nubeero.cia.claims.dto.ClaimRequiredDocumentResponse;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.product.ClaimDocumentRequirement;
import com.nubeero.cia.setup.product.ClaimDocumentRequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Derives the per-claim required-document checklist (B12).
 *
 * <p>No table of its own — the view is computed at request time from:
 * <ul>
 *   <li>{@link ClaimDocumentRequirement} rows for the claim's product, and</li>
 *   <li>uploaded {@link ClaimDocument} rows for the claim.</li>
 * </ul>
 *
 * <p>A requirement is considered "received" when at least one non-deleted
 * ClaimDocument exists with the same {@link ClaimDocumentType} on the claim.
 * Requirements without a documentType are returned as
 * {@code mappable=false, received=false} — they're informational rows that
 * can't be auto-tracked until an admin updates the setup with a type.
 *
 * <p>Computation cost is O(R + D) per call where R is the count of
 * requirements (typically 5–10 per product) and D is the count of uploaded
 * documents on the claim — small enough to derive on every read without
 * caching.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClaimRequiredDocumentService {

    private final ClaimRepository                    claimRepository;
    private final ClaimDocumentRepository            documentRepository;
    private final ClaimDocumentRequirementRepository requirementRepository;

    public List<ClaimRequiredDocumentResponse> list(UUID claimId) {
        Claim claim = claimRepository.findByIdAndDeletedAtIsNull(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Claim", claimId));

        List<ClaimDocumentRequirement> requirements =
                requirementRepository.findAllByProductIdAndDeletedAtIsNull(claim.getProductId());

        // Build a fast lookup: documentType -> first uploaded document of that type.
        // "First" by createdAt ASC so the earliest receipt is what shows on the row.
        Map<ClaimDocumentType, ClaimDocument> uploaded =
                documentRepository.findAllByClaim_IdAndDeletedAtIsNull(claimId).stream()
                        .sorted(Comparator.comparing(ClaimDocument::getCreatedAt))
                        .collect(Collectors.toMap(
                                ClaimDocument::getDocumentType,
                                d -> d,
                                (first, second) -> first));  // keep earliest

        return requirements.stream().map(req -> {
            ClaimDocumentType type = parseType(req.getDocumentType());
            ClaimDocument match = type == null ? null : uploaded.get(type);
            return new ClaimRequiredDocumentResponse(
                    req.getId(),
                    req.getDocumentName(),
                    req.isMandatory(),
                    type,
                    type != null,
                    match != null,
                    match == null ? null : match.getId(),
                    match == null ? null : match.getFileName(),
                    match == null ? null : match.getCreatedAt()
            );
        }).toList();
    }

    /**
     * Tolerant enum lookup — invalid stored values (legacy data, typos)
     * resolve to {@code null} rather than throwing IllegalArgumentException
     * so the rest of the checklist keeps rendering.
     */
    private ClaimDocumentType parseType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return ClaimDocumentType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
