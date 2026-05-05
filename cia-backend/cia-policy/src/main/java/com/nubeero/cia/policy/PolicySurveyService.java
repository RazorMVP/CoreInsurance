package com.nubeero.cia.policy;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.policy.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Pre-loss policy survey workflow. Backed by a single PolicySurvey row
 * per Policy (1:1 enforced by unique constraint at the schema level).
 *
 * <p>Lifecycle: ASSIGNED → REPORT_SUBMITTED → APPROVED.
 * Anywhere → OVERRIDDEN if the underwriter waives the requirement.
 *
 * <p>Survey is only meaningful while the policy is in DRAFT or
 * PENDING_APPROVAL — once approved (ACTIVE) the survey cannot be
 * modified. The frontend hides the actions accordingly.
 */
@Service
@RequiredArgsConstructor
public class PolicySurveyService {

    private final PolicyRepository       policyRepository;
    private final PolicySurveyRepository surveyRepository;
    private final AuditService           auditService;

    @Transactional(readOnly = true)
    public PolicySurveyResponse get(UUID policyId) {
        PolicySurvey survey = surveyRepository
                .findByPolicyIdAndDeletedAtIsNull(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("PolicySurvey", policyId.toString()));
        return toResponse(survey);
    }

    @Transactional(readOnly = true)
    public PolicySurveyResponse getOrNull(UUID policyId) {
        return surveyRepository
                .findByPolicyIdAndDeletedAtIsNull(policyId)
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional
    public PolicySurveyResponse assignSurveyor(UUID policyId, AssignSurveyorRequest request) {
        Policy policy = findPolicyOrThrow(policyId);
        requireMutableStatus(policy, "Surveyor can only be assigned while the policy is in DRAFT or PENDING_APPROVAL");

        PolicySurvey survey = surveyRepository
                .findByPolicyIdAndDeletedAtIsNull(policyId)
                .orElseGet(() -> PolicySurvey.builder().policyId(policyId).build());

        if (survey.getStatus() == SurveyStatus.OVERRIDDEN) {
            throw new BusinessRuleException("SURVEY_OVERRIDDEN",
                    "Survey requirement was overridden — re-assignment is not permitted");
        }

        String userId = currentUserId();
        survey.setSurveyorType(request.getSurveyorType());
        survey.setSurveyorId(request.getSurveyorId());
        survey.setSurveyorName(request.getSurveyorName());
        survey.setAssignedBy(userId);
        survey.setAssignedAt(Instant.now());
        survey.setStatus(SurveyStatus.ASSIGNED);
        // Re-assignment clears the prior report so the new surveyor's submission isn't merged into the previous attempt.
        survey.setReportPath(null);
        survey.setReportNotes(null);
        survey.setReportUploadedBy(null);
        survey.setReportUploadedAt(null);

        PolicySurvey saved = surveyRepository.save(survey);
        auditService.log("PolicySurvey", saved.getId().toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public PolicySurveyResponse submitReport(UUID policyId, SurveyReportRequest request) {
        Policy policy = findPolicyOrThrow(policyId);
        requireMutableStatus(policy, "Survey report can only be submitted while the policy is in DRAFT or PENDING_APPROVAL");

        PolicySurvey survey = surveyRepository
                .findByPolicyIdAndDeletedAtIsNull(policyId)
                .orElseThrow(() -> new BusinessRuleException("SURVEY_NOT_ASSIGNED",
                        "Cannot submit a report before a surveyor is assigned"));

        if (survey.getStatus() == SurveyStatus.APPROVED || survey.getStatus() == SurveyStatus.OVERRIDDEN) {
            throw new BusinessRuleException("SURVEY_TERMINAL",
                    "Survey is already " + survey.getStatus().name().toLowerCase() + "; cannot submit a new report");
        }
        if ((request.getReportPath() == null || request.getReportPath().isBlank())
                && (request.getNotes() == null || request.getNotes().isBlank())) {
            throw new BusinessRuleException("EMPTY_REPORT",
                    "Provide a report file path, notes, or both");
        }

        survey.setReportPath(request.getReportPath());
        survey.setReportNotes(request.getNotes());
        survey.setReportUploadedBy(currentUserId());
        survey.setReportUploadedAt(Instant.now());
        survey.setStatus(SurveyStatus.REPORT_SUBMITTED);

        PolicySurvey saved = surveyRepository.save(survey);
        auditService.log("PolicySurvey", saved.getId().toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public PolicySurveyResponse approve(UUID policyId, ApproveSurveyRequest request) {
        Policy policy = findPolicyOrThrow(policyId);
        requireMutableStatus(policy, "Survey can only be approved while the policy is in DRAFT or PENDING_APPROVAL");

        PolicySurvey survey = surveyRepository
                .findByPolicyIdAndDeletedAtIsNull(policyId)
                .orElseThrow(() -> new BusinessRuleException("SURVEY_NOT_ASSIGNED",
                        "Cannot approve before a surveyor is assigned and a report submitted"));

        if (survey.getStatus() != SurveyStatus.REPORT_SUBMITTED) {
            throw new BusinessRuleException("INVALID_SURVEY_STATUS",
                    "Survey must be in REPORT_SUBMITTED before approval; current: " + survey.getStatus());
        }

        survey.setApprovedBy(currentUserId());
        survey.setApprovedAt(Instant.now());
        survey.setApprovalNotes(request == null ? null : request.getNotes());
        survey.setStatus(SurveyStatus.APPROVED);

        PolicySurvey saved = surveyRepository.save(survey);
        auditService.log("PolicySurvey", saved.getId().toString(), AuditAction.APPROVE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public PolicySurveyResponse override(UUID policyId, OverrideSurveyRequest request) {
        Policy policy = findPolicyOrThrow(policyId);
        requireMutableStatus(policy, "Survey can only be overridden while the policy is in DRAFT or PENDING_APPROVAL");

        PolicySurvey survey = surveyRepository
                .findByPolicyIdAndDeletedAtIsNull(policyId)
                .orElseGet(() -> PolicySurvey.builder().policyId(policyId).build());

        survey.setOverrideReason(request.getReason());
        survey.setOverriddenBy(currentUserId());
        survey.setOverriddenAt(Instant.now());
        survey.setStatus(SurveyStatus.OVERRIDDEN);

        PolicySurvey saved = surveyRepository.save(survey);
        auditService.log("PolicySurvey", saved.getId().toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private Policy findPolicyOrThrow(UUID id) {
        return policyRepository.findById(id)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", id.toString()));
    }

    private void requireMutableStatus(Policy policy, String message) {
        PolicyStatus s = policy.getStatus();
        if (s != PolicyStatus.DRAFT && s != PolicyStatus.PENDING_APPROVAL) {
            throw new BusinessRuleException("INVALID_POLICY_STATUS", message);
        }
    }

    private String currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return "system";
    }

    PolicySurveyResponse toResponse(PolicySurvey s) {
        return PolicySurveyResponse.builder()
                .id(s.getId())
                .policyId(s.getPolicyId())
                .status(s.getStatus())
                .surveyorType(s.getSurveyorType())
                .surveyorId(s.getSurveyorId())
                .surveyorName(s.getSurveyorName())
                .assignedBy(s.getAssignedBy())
                .assignedAt(s.getAssignedAt())
                .reportPath(s.getReportPath())
                .reportNotes(s.getReportNotes())
                .reportUploadedBy(s.getReportUploadedBy())
                .reportUploadedAt(s.getReportUploadedAt())
                .approvedBy(s.getApprovedBy())
                .approvedAt(s.getApprovedAt())
                .approvalNotes(s.getApprovalNotes())
                .overrideReason(s.getOverrideReason())
                .overriddenBy(s.getOverriddenBy())
                .overriddenAt(s.getOverriddenAt())
                .createdAt(s.getCreatedAt())
                .build();
    }
}
