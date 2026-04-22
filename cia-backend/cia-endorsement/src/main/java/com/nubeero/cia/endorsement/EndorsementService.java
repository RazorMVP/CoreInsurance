package com.nubeero.cia.endorsement;

import com.nubeero.cia.common.event.EndorsementApprovedEvent;
import com.nubeero.cia.documents.DocumentGenerationService;
import com.nubeero.cia.documents.EndorsementDocumentContext;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.endorsement.dto.*;
import com.nubeero.cia.policy.Policy;
import com.nubeero.cia.policy.PolicyRepository;
import com.nubeero.cia.policy.PolicyStatus;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EndorsementService {

    private final EndorsementRepository endorsementRepository;
    private final EndorsementNumberService numberService;
    private final PolicyRepository policyRepository;
    private final WorkflowClient workflowClient;
    private final ApplicationEventPublisher eventPublisher;
    private final DocumentGenerationService documentGenerationService;

    // ─── Queries ──────────────────────────────────────────────────────────

    public Page<Endorsement> list(UUID policyId, EndorsementStatus status, UUID customerId,
                                   Pageable pageable) {
        if (policyId != null) {
            return endorsementRepository.findAllByPolicyIdAndDeletedAtIsNull(policyId, pageable);
        } else if (status != null) {
            return endorsementRepository.findAllByStatusAndDeletedAtIsNull(status, pageable);
        } else if (customerId != null) {
            return endorsementRepository.findAllByCustomerIdAndDeletedAtIsNull(customerId, pageable);
        }
        return endorsementRepository.findAllByDeletedAtIsNull(pageable);
    }

    public Endorsement findOrThrow(UUID id) {
        return endorsementRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Endorsement", id));
    }

    // ─── Create ───────────────────────────────────────────────────────────

    @Transactional
    public Endorsement create(CreateEndorsementRequest req) {
        Policy policy = findActivePolicy(req.policyId());
        validateEffectiveDate(req.effectiveDate(), policy);

        long remainingDays = ChronoUnit.DAYS.between(req.effectiveDate(), policy.getPolicyEndDate());
        if (remainingDays <= 0) {
            throw new BusinessRuleException("INVALID_EFFECTIVE_DATE",
                    "Effective date must be before policy end date");
        }

        BigDecimal premiumAdjustment = calculatePremiumAdjustment(
                policy.getNetPremium(), req.newNetPremium(), remainingDays);

        EndorsementType type = deriveType(premiumAdjustment);

        Endorsement endorsement = Endorsement.builder()
                .endorsementNumber(numberService.next())
                .policyId(policy.getId())
                .policyNumber(policy.getPolicyNumber())
                .customerId(policy.getCustomerId())
                .customerName(policy.getCustomerName())
                .productId(policy.getProductId())
                .productName(policy.getProductName())
                .productCode(policy.getProductCode())
                .productRate(policy.getProductRate())
                .classOfBusinessId(policy.getClassOfBusinessId())
                .classOfBusinessName(policy.getClassOfBusinessName())
                .brokerId(policy.getBrokerId())
                .brokerName(policy.getBrokerName())
                .effectiveDate(req.effectiveDate())
                .policyEndDate(policy.getPolicyEndDate())
                .remainingDays((int) remainingDays)
                .oldSumInsured(policy.getTotalSumInsured())
                .newSumInsured(req.newSumInsured() != null ? req.newSumInsured() : policy.getTotalSumInsured())
                .oldNetPremium(policy.getNetPremium())
                .newNetPremium(req.newNetPremium() != null ? req.newNetPremium() : policy.getNetPremium())
                .premiumAdjustment(premiumAdjustment)
                .endorsementType(type)
                .description(req.description())
                .notes(req.notes())
                .build();

        // Snapshot risks — start from current policy risks, then apply overrides if provided
        if (req.risks() != null && !req.risks().isEmpty()) {
            AtomicInteger order = new AtomicInteger(1);
            req.risks().forEach(r -> endorsement.getRisks().add(EndorsementRisk.builder()
                    .endorsement(endorsement)
                    .description(r.description())
                    .sumInsured(r.sumInsured())
                    .premium(r.premium())
                    .sectionId(r.sectionId())
                    .sectionName(r.sectionName())
                    .riskDetails(r.riskDetails())
                    .vehicleRegNumber(r.vehicleRegNumber())
                    .orderNo(order.getAndIncrement())
                    .build()));
        }

        return endorsementRepository.save(endorsement);
    }

    // ─── Submit for approval ──────────────────────────────────────────────

    @Transactional
    public Endorsement submitForApproval(UUID id) {
        Endorsement e = findOrThrow(id);
        if (e.getStatus() != EndorsementStatus.DRAFT) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Only DRAFT endorsements can be submitted for approval");
        }
        e.setStatus(EndorsementStatus.PENDING_APPROVAL);

        try {
            ApprovalWorkflow workflow = workflowClient.newWorkflowStub(ApprovalWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId("endorsement-approval-" + id)
                            .setTaskQueue(TemporalQueues.APPROVAL_QUEUE)
                            .build());
            WorkflowClient.start(workflow::runApproval,
                    ApprovalRequest.builder()
                            .entityType("ENDORSEMENT")
                            .entityId(id.toString())
                            .tenantId(com.nubeero.cia.common.tenant.TenantContext.getTenantId())
                            .initiatedBy(SecurityContextHolder.getContext().getAuthentication().getName())
                            .amount(e.getPremiumAdjustment().abs())
                            .currency("NGN")
                            .build());
            e.setWorkflowId("endorsement-approval-" + id);
        } catch (Exception ex) {
            log.warn("Temporal unavailable for endorsement {}: {}", id, ex.getMessage());
        }

        return endorsementRepository.save(e);
    }

    // ─── Approve ──────────────────────────────────────────────────────────

    @Transactional
    public Endorsement approve(UUID id, String notes) {
        Endorsement e = findOrThrow(id);
        if (e.getStatus() != EndorsementStatus.PENDING_APPROVAL) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Only PENDING_APPROVAL endorsements can be approved");
        }

        String approver = currentUser();
        e.setStatus(EndorsementStatus.APPROVED);
        e.setApprovedBy(approver);
        e.setApprovedAt(Instant.now());
        Endorsement saved = endorsementRepository.save(e);

        // Generate and store endorsement PDF
        String docPath = documentGenerationService.generateEndorsementDocument(
                new EndorsementDocumentContext(
                        saved.getId(), saved.getEndorsementNumber(),
                        saved.getProductId(), saved.getClassOfBusinessId(),
                        saved.getPolicyNumber(), saved.getCustomerName(),
                        saved.getEndorsementType().name(),
                        saved.getEffectiveDate(), saved.getPolicyEndDate(),
                        saved.getOldSumInsured(), saved.getNewSumInsured(),
                        saved.getPremiumAdjustment(), saved.getCurrencyCode(),
                        saved.getDescription(), approver,
                        saved.getApprovedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate()));
        if (docPath != null) {
            saved.setDocumentPath(docPath);
            saved = endorsementRepository.save(saved);
        }

        // Publish event — cia-finance will create debit or credit note
        eventPublisher.publishEvent(new EndorsementApprovedEvent(
                saved.getId(),
                saved.getEndorsementNumber(),
                saved.getPolicyId(),
                saved.getPolicyNumber(),
                saved.getCustomerId(),
                saved.getCustomerName(),
                saved.getBrokerId(),
                saved.getBrokerName(),
                saved.getProductName(),
                saved.getPremiumAdjustment(),
                saved.getCurrencyCode()
        ));

        return saved;
    }

    // ─── Reject ───────────────────────────────────────────────────────────

    @Transactional
    public Endorsement reject(UUID id, String reason) {
        Endorsement e = findOrThrow(id);
        if (e.getStatus() != EndorsementStatus.PENDING_APPROVAL) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Only PENDING_APPROVAL endorsements can be rejected");
        }
        e.setStatus(EndorsementStatus.REJECTED);
        e.setRejectedBy(currentUser());
        e.setRejectedAt(Instant.now());
        e.setRejectionReason(reason);
        return endorsementRepository.save(e);
    }

    // ─── Cancel ───────────────────────────────────────────────────────────

    @Transactional
    public Endorsement cancel(UUID id, String reason) {
        Endorsement e = findOrThrow(id);
        if (e.getStatus() == EndorsementStatus.APPROVED
                || e.getStatus() == EndorsementStatus.CANCELLED) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Cannot cancel an APPROVED or already CANCELLED endorsement");
        }
        e.setStatus(EndorsementStatus.CANCELLED);
        e.setCancelledBy(currentUser());
        e.setCancelledAt(Instant.now());
        e.setCancellationReason(reason);
        return endorsementRepository.save(e);
    }

    // ─── Premium calculation helper (also used by controller for preview) ─

    public BigDecimal calculatePremiumAdjustment(BigDecimal oldPremium, BigDecimal newPremium,
                                                  long remainingDays) {
        if (newPremium == null || newPremium.compareTo(oldPremium) == 0) {
            return BigDecimal.ZERO;
        }
        // Pro-rata: (newAnnual - oldAnnual) * remainingDays / 365
        BigDecimal diff = newPremium.subtract(oldPremium);
        return diff.multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    private Policy findActivePolicy(UUID policyId) {
        Policy policy = policyRepository.findById(policyId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", policyId));
        if (policy.getStatus() != PolicyStatus.ACTIVE
                && policy.getStatus() != PolicyStatus.REINSTATED) {
            throw new BusinessRuleException("POLICY_NOT_ACTIVE",
                    "Endorsements can only be created on ACTIVE or REINSTATED policies");
        }
        return policy;
    }

    private void validateEffectiveDate(LocalDate effectiveDate, Policy policy) {
        if (effectiveDate.isBefore(LocalDate.now())) {
            throw new BusinessRuleException("INVALID_EFFECTIVE_DATE",
                    "Effective date cannot be in the past");
        }
        if (!effectiveDate.isBefore(policy.getPolicyEndDate())) {
            throw new BusinessRuleException("INVALID_EFFECTIVE_DATE",
                    "Effective date must be before policy end date");
        }
    }

    private EndorsementType deriveType(BigDecimal premiumAdjustment) {
        int cmp = premiumAdjustment.compareTo(BigDecimal.ZERO);
        if (cmp > 0) return EndorsementType.ADDITIONAL_PREMIUM;
        if (cmp < 0) return EndorsementType.RETURN_PREMIUM;
        return EndorsementType.NON_PREMIUM_BEARING;
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
