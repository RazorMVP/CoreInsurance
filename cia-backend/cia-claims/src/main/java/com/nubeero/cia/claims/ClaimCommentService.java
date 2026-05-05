package com.nubeero.cia.claims;

import com.nubeero.cia.claims.dto.AddClaimCommentRequest;
import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Per-claim comment feed. Append-only by design — comments are an audit
 * trail of decisions, not editable correspondence. Soft-delete is supported
 * via BaseEntity for compliance/incident moderation, but is not exposed
 * through the controller.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClaimCommentService {

    private final ClaimCommentRepository commentRepository;
    private final ClaimRepository        claimRepository;
    private final AuditService           auditService;

    public Page<ClaimComment> list(UUID claimId, Pageable pageable) {
        // Validate the claim exists so callers get a clean 404 rather than
        // an empty list when they hit a wrong ID.
        claimRepository.findByIdAndDeletedAtIsNull(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Claim", claimId));
        return commentRepository.findAllByClaim_IdAndDeletedAtIsNullOrderByCreatedAtDesc(
                claimId, pageable);
    }

    @Transactional
    public ClaimComment add(UUID claimId, AddClaimCommentRequest req) {
        Claim claim = claimRepository.findByIdAndDeletedAtIsNull(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Claim", claimId));

        ClaimComment comment = ClaimComment.builder()
                .claim(claim)
                .body(req.body().trim())
                .authorName(currentDisplayName())
                .build();

        ClaimComment saved = commentRepository.save(comment);
        auditService.log("ClaimComment", saved.getId().toString(),
                AuditAction.CREATE, null, saved);
        return saved;
    }

    /**
     * Best-effort display name from the JWT — falls back to the auth
     * subject (typically a UUID) and finally to "system" for service calls.
     * Mirrors the resolution used elsewhere in cia-claims.
     */
    private String currentDisplayName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return "system";
        if (auth.getPrincipal() instanceof Jwt jwt) {
            String name = jwt.getClaimAsString("name");
            if (name != null && !name.isBlank()) return name;
            String preferred = jwt.getClaimAsString("preferred_username");
            if (preferred != null && !preferred.isBlank()) return preferred;
        }
        return auth.getName();
    }
}
