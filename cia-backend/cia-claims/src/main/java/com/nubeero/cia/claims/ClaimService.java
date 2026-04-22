package com.nubeero.cia.claims;

import com.nubeero.cia.common.event.ClaimApprovedEvent;
import com.nubeero.cia.common.event.ClaimSettledEvent;
import com.nubeero.cia.documents.ClaimDvContext;
import com.nubeero.cia.documents.DocumentGenerationService;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.claims.dto.*;
import com.nubeero.cia.policy.Policy;
import com.nubeero.cia.policy.PolicyRepository;
import com.nubeero.cia.policy.PolicyStatus;
import com.nubeero.cia.setup.org.Surveyor;
import com.nubeero.cia.setup.org.SurveyorRepository;
import com.nubeero.cia.workflow.TemporalQueues;
import com.nubeero.cia.workflow.approval.ApprovalRequest;
import com.nubeero.cia.workflow.approval.ApprovalWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClaimService {

    private final ClaimRepository claimRepository;
    private final ClaimNumberService numberService;
    private final PolicyRepository policyRepository;
    private final SurveyorRepository surveyorRepository;
    private final WorkflowClient workflowClient;
    private final ApplicationEventPublisher eventPublisher;
    private final DocumentGenerationService documentGenerationService;

    // ─── Queries ──────────────────────────────────────────────────────────

    public Page<Claim> list(UUID policyId, ClaimStatus status, UUID customerId, Pageable pageable) {
        if (policyId != null) return claimRepository.findAllByPolicyIdAndDeletedAtIsNull(policyId, pageable);
        if (status != null)   return claimRepository.findAllByStatusAndDeletedAtIsNull(status, pageable);
        if (customerId != null) return claimRepository.findAllByCustomerIdAndDeletedAtIsNull(customerId, pageable);
        return claimRepository.findAllByDeletedAtIsNull(pageable);
    }

    public Page<Claim> search(String query, Pageable pageable) {
        return claimRepository.search(query, pageable);
    }

    public Claim findOrThrow(UUID id) {
        return claimRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Claim", id));
    }

    // ─── Register ─────────────────────────────────────────────────────────

    @Transactional
    public Claim register(RegisterClaimRequest req) {
        Policy policy = policyRepository.findById(req.policyId())
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", req.policyId()));

        if (policy.getStatus() != PolicyStatus.ACTIVE
                && policy.getStatus() != PolicyStatus.REINSTATED) {
            throw new BusinessRuleException("POLICY_NOT_ACTIVE",
                    "Claims can only be registered on ACTIVE or REINSTATED policies");
        }

        if (req.incidentDate().isAfter(req.reportedDate())) {
            throw new BusinessRuleException("INVALID_DATES",
                    "Incident date cannot be after reported date");
        }

        Claim claim = Claim.builder()
                .claimNumber(numberService.next())
                .policyId(policy.getId())
                .policyNumber(policy.getPolicyNumber())
                .policyStartDate(policy.getPolicyStartDate())
                .policyEndDate(policy.getPolicyEndDate())
                .customerId(policy.getCustomerId())
                .customerName(policy.getCustomerName())
                .productId(policy.getProductId())
                .productName(policy.getProductName())
                .classOfBusinessId(policy.getClassOfBusinessId())
                .classOfBusinessName(policy.getClassOfBusinessName())
                .brokerId(policy.getBrokerId())
                .brokerName(policy.getBrokerName())
                .incidentDate(req.incidentDate())
                .reportedDate(req.reportedDate())
                .lossLocation(req.lossLocation())
                .description(req.description())
                .estimatedLoss(req.estimatedLoss())
                .notes(req.notes())
                .build();

        return claimRepository.save(claim);
    }

    // ─── Update details ───────────────────────────────────────────────────

    @Transactional
    public Claim updateDetails(UUID id, UpdateClaimRequest req) {
        Claim claim = findOrThrow(id);
        requireDraftOrInvestigation(claim);
        if (req.lossLocation() != null) claim.setLossLocation(req.lossLocation());
        if (req.description() != null) claim.setDescription(req.description());
        if (req.estimatedLoss() != null) claim.setEstimatedLoss(req.estimatedLoss());
        if (req.notes() != null) claim.setNotes(req.notes());
        return claimRepository.save(claim);
    }

    // ─── Assign surveyor ──────────────────────────────────────────────────

    @Transactional
    public Claim assignSurveyor(UUID claimId, UUID surveyorId) {
        Claim claim = findOrThrow(claimId);
        if (claim.getStatus() == ClaimStatus.APPROVED
                || claim.getStatus() == ClaimStatus.SETTLED
                || claim.getStatus() == ClaimStatus.REJECTED
                || claim.getStatus() == ClaimStatus.WITHDRAWN) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Cannot assign surveyor to a " + claim.getStatus() + " claim");
        }

        Surveyor surveyor = surveyorRepository.findById(surveyorId)
                .filter(s -> s.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Surveyor", surveyorId));

        claim.setSurveyorId(surveyor.getId());
        claim.setSurveyorName(surveyor.getName());
        claim.setSurveyorAssignedAt(Instant.now());
        if (claim.getStatus() == ClaimStatus.REGISTERED) {
            claim.setStatus(ClaimStatus.UNDER_INVESTIGATION);
        }
        return claimRepository.save(claim);
    }

    // ─── Set / update reserve ─────────────────────────────────────────────

    @Transactional
    public Claim setReserve(UUID claimId, SetReserveRequest req) {
        Claim claim = findOrThrow(claimId);
        if (claim.getStatus() == ClaimStatus.APPROVED
                || claim.getStatus() == ClaimStatus.SETTLED
                || claim.getStatus() == ClaimStatus.REJECTED
                || claim.getStatus() == ClaimStatus.WITHDRAWN) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Cannot set reserve on a " + claim.getStatus() + " claim");
        }

        ClaimReserve reserve = ClaimReserve.builder()
                .claim(claim)
                .amount(req.amount())
                .previousAmount(claim.getReserveAmount())
                .reason(req.reason())
                .build();
        claim.getReserves().add(reserve);
        claim.setReserveAmount(req.amount());
        if (claim.getStatus() == ClaimStatus.REGISTERED
                || claim.getStatus() == ClaimStatus.UNDER_INVESTIGATION) {
            claim.setStatus(ClaimStatus.RESERVED);
        }
        return claimRepository.save(claim);
    }

    // ─── Submit for DV approval ───────────────────────────────────────────

    @Transactional
    public Claim submitForApproval(UUID id, SubmitClaimRequest req) {
        Claim claim = findOrThrow(id);
        if (claim.getStatus() != ClaimStatus.RESERVED) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Only RESERVED claims can be submitted for DV approval");
        }
        if (req.approvedAmount() != null) {
            claim.setApprovedAmount(req.approvedAmount());
        } else {
            claim.setApprovedAmount(claim.getReserveAmount());
        }
        claim.setStatus(ClaimStatus.PENDING_APPROVAL);

        try {
            ApprovalWorkflow workflow = workflowClient.newWorkflowStub(ApprovalWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId("claim-approval-" + id)
                            .setTaskQueue(TemporalQueues.APPROVAL_QUEUE)
                            .build());
            WorkflowClient.start(workflow::runApproval,
                    ApprovalRequest.builder()
                            .entityType("CLAIM")
                            .entityId(id.toString())
                            .tenantId(com.nubeero.cia.common.tenant.TenantContext.getTenantId())
                            .initiatedBy(SecurityContextHolder.getContext().getAuthentication().getName())
                            .amount(claim.getApprovedAmount())
                            .currency("NGN")
                            .build());
            claim.setWorkflowId("claim-approval-" + id);
        } catch (Exception ex) {
            log.warn("Temporal unavailable for claim {}: {}", id, ex.getMessage());
        }

        return claimRepository.save(claim);
    }

    // ─── Approve (DV execution) ───────────────────────────────────────────

    @Transactional
    public Claim approve(UUID id) {
        Claim claim = findOrThrow(id);
        if (claim.getStatus() != ClaimStatus.PENDING_APPROVAL) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Only PENDING_APPROVAL claims can be approved");
        }
        claim.setStatus(ClaimStatus.APPROVED);
        claim.setApprovedBy(currentUser());
        claim.setApprovedAt(Instant.now());
        Claim saved = claimRepository.save(claim);

        // Generate and store discharge voucher PDF
        String dvPath = documentGenerationService.generateClaimDv(new ClaimDvContext(
                saved.getId(), saved.getClaimNumber(),
                saved.getProductId(), saved.getClassOfBusinessId(),
                saved.getPolicyNumber(), saved.getCustomerName(),
                saved.getIncidentDate(), saved.getDescription(),
                saved.getApprovedAmount(), saved.getCurrencyCode(),
                saved.getApprovedBy(),
                saved.getApprovedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate()));
        if (dvPath != null) {
            saved.setDvDocumentPath(dvPath);
            saved = claimRepository.save(saved);
        }

        eventPublisher.publishEvent(new ClaimApprovedEvent(
                saved.getId(),
                saved.getClaimNumber(),
                saved.getPolicyId(),
                saved.getPolicyNumber(),
                saved.getCustomerId(),
                saved.getCustomerName(),
                saved.getBrokerId(),
                saved.getBrokerName(),
                saved.getProductName(),
                saved.getApprovedAmount(),
                saved.getCurrencyCode()
        ));

        return saved;
    }

    // ─── Reject ───────────────────────────────────────────────────────────

    @Transactional
    public Claim reject(UUID id, String reason) {
        Claim claim = findOrThrow(id);
        if (claim.getStatus() != ClaimStatus.PENDING_APPROVAL) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Only PENDING_APPROVAL claims can be rejected");
        }
        claim.setStatus(ClaimStatus.REJECTED);
        claim.setRejectedBy(currentUser());
        claim.setRejectedAt(Instant.now());
        claim.setRejectionReason(reason);
        return claimRepository.save(claim);
    }

    // ─── Withdraw ─────────────────────────────────────────────────────────

    @Transactional
    public Claim withdraw(UUID id, String reason) {
        Claim claim = findOrThrow(id);
        if (claim.getStatus() == ClaimStatus.APPROVED
                || claim.getStatus() == ClaimStatus.SETTLED
                || claim.getStatus() == ClaimStatus.WITHDRAWN) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Cannot withdraw a " + claim.getStatus() + " claim");
        }
        claim.setStatus(ClaimStatus.WITHDRAWN);
        claim.setWithdrawnBy(currentUser());
        claim.setWithdrawnAt(Instant.now());
        claim.setWithdrawalReason(reason);
        return claimRepository.save(claim);
    }

    // ─── Mark settled (called after finance confirms payment) ─────────────

    @Transactional
    public Claim markSettled(UUID id) {
        Claim claim = findOrThrow(id);
        if (claim.getStatus() != ClaimStatus.APPROVED) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Only APPROVED claims can be marked as settled");
        }
        claim.setStatus(ClaimStatus.SETTLED);
        Instant settledAt = Instant.now();
        claim.setSettledAt(settledAt);
        Claim saved = claimRepository.save(claim);
        eventPublisher.publishEvent(new ClaimSettledEvent(
                saved.getId(), saved.getClaimNumber(),
                saved.getPolicyId(), saved.getPolicyNumber(),
                saved.getCustomerId(), saved.getCustomerName(),
                settledAt));
        return saved;
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    private void requireDraftOrInvestigation(Claim claim) {
        if (claim.getStatus() != ClaimStatus.REGISTERED
                && claim.getStatus() != ClaimStatus.UNDER_INVESTIGATION) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Claim details can only be updated in REGISTERED or UNDER_INVESTIGATION status");
        }
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
