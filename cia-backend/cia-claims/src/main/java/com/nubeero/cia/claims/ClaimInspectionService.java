package com.nubeero.cia.claims;

import com.nubeero.cia.claims.dto.*;
import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Post-loss claim inspection workflow. One ClaimInspection row per Claim
 * (1:1 enforced by unique constraint at the schema level).
 *
 * <p>Lifecycle:
 *   ASSIGNED → REPORT_SUBMITTED → APPROVED
 *   REPORT_SUBMITTED → DECLINED → ASSIGNED (re-assigned + new report)
 *   any → OVERRIDDEN (waived with reason)
 *
 * <p>Inspection actions are only meaningful while the claim is in
 * REGISTERED, UNDER_INVESTIGATION, RESERVED or PENDING_APPROVAL — once
 * APPROVED / SETTLED / REJECTED / WITHDRAWN the inspection is locked.
 *
 * <p>Mirrors PolicySurveyService (B4.3) closely; the additional DECLINED
 * transition reflects post-loss inspection reports being commonly bounced
 * for incomplete or inconsistent findings.
 */
@Service
@RequiredArgsConstructor
public class ClaimInspectionService {

    private final ClaimRepository           claimRepository;
    private final ClaimInspectionRepository inspectionRepository;
    private final AuditService              auditService;

    @Transactional(readOnly = true)
    public ClaimInspectionResponse get(UUID claimId) {
        ClaimInspection inspection = inspectionRepository
                .findByClaimIdAndDeletedAtIsNull(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("ClaimInspection", claimId.toString()));
        return toResponse(inspection);
    }

    @Transactional(readOnly = true)
    public ClaimInspectionResponse getOrNull(UUID claimId) {
        return inspectionRepository
                .findByClaimIdAndDeletedAtIsNull(claimId)
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional
    public ClaimInspectionResponse assignInspector(UUID claimId, AssignInspectorRequest request) {
        Claim claim = findClaimOrThrow(claimId);
        requireMutableStatus(claim, "Inspector can only be assigned while the claim is open");

        ClaimInspection inspection = inspectionRepository
                .findByClaimIdAndDeletedAtIsNull(claimId)
                .orElseGet(() -> ClaimInspection.builder().claimId(claimId).build());

        if (inspection.getStatus() == InspectionStatus.OVERRIDDEN
                || inspection.getStatus() == InspectionStatus.APPROVED) {
            throw new BusinessRuleException("INSPECTION_TERMINAL",
                    "Inspection is already " + inspection.getStatus().name().toLowerCase()
                    + "; re-assignment is not permitted");
        }

        String userId = currentUserId();
        inspection.setSurveyorType(request.getSurveyorType());
        inspection.setSurveyorId(request.getSurveyorId());
        inspection.setSurveyorName(request.getSurveyorName());
        inspection.setAssignedBy(userId);
        inspection.setAssignedAt(Instant.now());
        inspection.setStatus(InspectionStatus.ASSIGNED);
        // Re-assignment clears the prior report so the new surveyor's submission
        // isn't merged into the previous attempt. Decline notes are also cleared.
        inspection.setReportPath(null);
        inspection.setReportNotes(null);
        inspection.setReportUploadedBy(null);
        inspection.setReportUploadedAt(null);
        inspection.setDeclinedBy(null);
        inspection.setDeclinedAt(null);
        inspection.setDeclineReason(null);

        ClaimInspection saved = inspectionRepository.save(inspection);
        auditService.log("ClaimInspection", saved.getId().toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public ClaimInspectionResponse submitReport(UUID claimId, InspectionReportRequest request) {
        Claim claim = findClaimOrThrow(claimId);
        requireMutableStatus(claim, "Inspection report can only be submitted while the claim is open");

        ClaimInspection inspection = inspectionRepository
                .findByClaimIdAndDeletedAtIsNull(claimId)
                .orElseThrow(() -> new BusinessRuleException("INSPECTION_NOT_ASSIGNED",
                        "Cannot submit a report before an inspector is assigned"));

        if (inspection.getStatus() == InspectionStatus.APPROVED
                || inspection.getStatus() == InspectionStatus.OVERRIDDEN) {
            throw new BusinessRuleException("INSPECTION_TERMINAL",
                    "Inspection is already " + inspection.getStatus().name().toLowerCase()
                    + "; cannot submit a new report");
        }
        if ((request.getReportPath() == null || request.getReportPath().isBlank())
                && (request.getNotes() == null || request.getNotes().isBlank())) {
            throw new BusinessRuleException("EMPTY_REPORT",
                    "Provide a report file path, notes, or both");
        }

        inspection.setReportPath(request.getReportPath());
        inspection.setReportNotes(request.getNotes());
        inspection.setReportUploadedBy(currentUserId());
        inspection.setReportUploadedAt(Instant.now());
        inspection.setStatus(InspectionStatus.REPORT_SUBMITTED);

        ClaimInspection saved = inspectionRepository.save(inspection);
        auditService.log("ClaimInspection", saved.getId().toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public ClaimInspectionResponse approve(UUID claimId, ApproveInspectionRequest request) {
        Claim claim = findClaimOrThrow(claimId);
        requireMutableStatus(claim, "Inspection can only be approved while the claim is open");

        ClaimInspection inspection = inspectionRepository
                .findByClaimIdAndDeletedAtIsNull(claimId)
                .orElseThrow(() -> new BusinessRuleException("INSPECTION_NOT_ASSIGNED",
                        "Cannot approve before an inspector is assigned and a report submitted"));

        if (inspection.getStatus() != InspectionStatus.REPORT_SUBMITTED) {
            throw new BusinessRuleException("INVALID_INSPECTION_STATUS",
                    "Inspection must be in REPORT_SUBMITTED before approval; current: " + inspection.getStatus());
        }

        inspection.setApprovedBy(currentUserId());
        inspection.setApprovedAt(Instant.now());
        inspection.setApprovalNotes(request == null ? null : request.getNotes());
        inspection.setStatus(InspectionStatus.APPROVED);

        ClaimInspection saved = inspectionRepository.save(inspection);
        auditService.log("ClaimInspection", saved.getId().toString(), AuditAction.APPROVE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public ClaimInspectionResponse decline(UUID claimId, DeclineInspectionRequest request) {
        Claim claim = findClaimOrThrow(claimId);
        requireMutableStatus(claim, "Inspection can only be declined while the claim is open");

        ClaimInspection inspection = inspectionRepository
                .findByClaimIdAndDeletedAtIsNull(claimId)
                .orElseThrow(() -> new BusinessRuleException("INSPECTION_NOT_ASSIGNED",
                        "Cannot decline a report that doesn't exist"));

        if (inspection.getStatus() != InspectionStatus.REPORT_SUBMITTED) {
            throw new BusinessRuleException("INVALID_INSPECTION_STATUS",
                    "Inspection must be in REPORT_SUBMITTED before it can be declined; current: " + inspection.getStatus());
        }

        inspection.setDeclinedBy(currentUserId());
        inspection.setDeclinedAt(Instant.now());
        inspection.setDeclineReason(request.getReason());
        inspection.setStatus(InspectionStatus.DECLINED);

        ClaimInspection saved = inspectionRepository.save(inspection);
        auditService.log("ClaimInspection", saved.getId().toString(), AuditAction.REJECT, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public ClaimInspectionResponse override(UUID claimId, OverrideInspectionRequest request) {
        Claim claim = findClaimOrThrow(claimId);
        requireMutableStatus(claim, "Inspection can only be overridden while the claim is open");

        ClaimInspection inspection = inspectionRepository
                .findByClaimIdAndDeletedAtIsNull(claimId)
                .orElseGet(() -> ClaimInspection.builder().claimId(claimId).build());

        inspection.setOverrideReason(request.getReason());
        inspection.setOverriddenBy(currentUserId());
        inspection.setOverriddenAt(Instant.now());
        inspection.setStatus(InspectionStatus.OVERRIDDEN);

        ClaimInspection saved = inspectionRepository.save(inspection);
        auditService.log("ClaimInspection", saved.getId().toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private Claim findClaimOrThrow(UUID id) {
        return claimRepository.findById(id)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Claim", id.toString()));
    }

    private void requireMutableStatus(Claim claim, String message) {
        ClaimStatus s = claim.getStatus();
        if (s == ClaimStatus.APPROVED || s == ClaimStatus.SETTLED
                || s == ClaimStatus.REJECTED || s == ClaimStatus.WITHDRAWN) {
            throw new BusinessRuleException("INVALID_CLAIM_STATUS", message);
        }
    }

    private String currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return "system";
    }

    ClaimInspectionResponse toResponse(ClaimInspection i) {
        return ClaimInspectionResponse.builder()
                .id(i.getId())
                .claimId(i.getClaimId())
                .status(i.getStatus())
                .surveyorType(i.getSurveyorType())
                .surveyorId(i.getSurveyorId())
                .surveyorName(i.getSurveyorName())
                .assignedBy(i.getAssignedBy())
                .assignedAt(i.getAssignedAt())
                .reportPath(i.getReportPath())
                .reportNotes(i.getReportNotes())
                .reportUploadedBy(i.getReportUploadedBy())
                .reportUploadedAt(i.getReportUploadedAt())
                .approvedBy(i.getApprovedBy())
                .approvedAt(i.getApprovedAt())
                .approvalNotes(i.getApprovalNotes())
                .declinedBy(i.getDeclinedBy())
                .declinedAt(i.getDeclinedAt())
                .declineReason(i.getDeclineReason())
                .overrideReason(i.getOverrideReason())
                .overriddenBy(i.getOverriddenBy())
                .overriddenAt(i.getOverriddenAt())
                .createdAt(i.getCreatedAt())
                .build();
    }
}
