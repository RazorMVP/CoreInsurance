package com.nubeero.cia.policy;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.documents.DocumentGenerationService;
import com.nubeero.cia.documents.PolicyDocumentContext;
import com.nubeero.cia.common.event.PolicyApprovedEvent;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.customer.Customer;
import com.nubeero.cia.customer.CustomerService;
import com.nubeero.cia.customer.CustomerType;
import com.nubeero.cia.policy.dto.*;
import com.nubeero.cia.quotation.BusinessType;
import com.nubeero.cia.quotation.Quote;
import com.nubeero.cia.quotation.QuoteService;
import com.nubeero.cia.quotation.QuoteStatus;
import com.nubeero.cia.setup.org.Broker;
import com.nubeero.cia.setup.org.BrokerRepository;
import com.nubeero.cia.setup.org.InsuranceCompany;
import com.nubeero.cia.setup.org.InsuranceCompanyRepository;
import com.nubeero.cia.setup.product.Product;
import com.nubeero.cia.setup.product.ProductRepository;
import com.nubeero.cia.setup.product.ProductSection;
import com.nubeero.cia.setup.product.PolicyNumberFormatService;
import com.nubeero.cia.workflow.TemporalQueues;
import com.nubeero.cia.workflow.approval.ApprovalRequest;
import com.nubeero.cia.workflow.approval.ApprovalWorkflow;
import com.nubeero.cia.workflow.naicom.NaicomUploadWorkflow;
import com.nubeero.cia.workflow.niid.NiidUploadWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyService {

    private final PolicyRepository repository;
    private final PolicyNumberFormatService policyNumberFormatService;
    private final CustomerService customerService;
    private final QuoteService quoteService;
    private final ProductRepository productRepository;
    private final BrokerRepository brokerRepository;
    private final InsuranceCompanyRepository insuranceCompanyRepository;
    private final com.nubeero.cia.setup.product.ClassOfBusinessRepository classOfBusinessRepository;
    private final AuditService auditService;
    private final WorkflowClient workflowClient;
    private final ApplicationEventPublisher eventPublisher;
    private final DocumentGenerationService documentGenerationService;

    // ─── Queries ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<PolicySummaryResponse> list(PolicyStatus status, UUID customerId, Pageable pageable) {
        Page<Policy> page;
        if (status != null) {
            page = repository.findAllByStatusAndDeletedAtIsNull(status, pageable);
        } else if (customerId != null) {
            page = repository.findAllByCustomerIdAndDeletedAtIsNull(customerId, pageable);
        } else {
            page = repository.findAllByDeletedAtIsNull(pageable);
        }
        return page.map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public Page<PolicySummaryResponse> search(String query, Pageable pageable) {
        return repository.search(query, pageable).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public PolicyResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    // ─── Bind from quote ──────────────────────────────────────────────────

    @Transactional
    public PolicyResponse bindFromQuote(UUID quoteId) {
        Quote quote = quoteService.findOrThrow(quoteId);
        if (quote.getStatus() != QuoteStatus.APPROVED) {
            throw new BusinessRuleException("QUOTE_NOT_APPROVED",
                    "Only approved quotes can be bound to a policy: " + quoteId);
        }
        if (repository.findByQuoteIdAndDeletedAtIsNull(quoteId).isPresent()) {
            throw new BusinessRuleException("QUOTE_ALREADY_BOUND",
                    "A policy already exists for quote: " + quoteId);
        }

        Policy policy = Policy.builder()
                .quoteId(quote.getId())
                .quoteNumber(quote.getQuoteNumber())
                .customerId(quote.getCustomerId())
                .customerName(quote.getCustomerName())
                .productId(quote.getProductId())
                .productName(quote.getProductName())
                .productCode(quote.getProductCode())
                .productRate(quote.getProductRate())
                .classOfBusinessId(quote.getClassOfBusinessId())
                .classOfBusinessName(quote.getClassOfBusinessName())
                .classOfBusinessCode(resolveClassCode(quote.getClassOfBusinessId()))
                .brokerId(quote.getBrokerId())
                .brokerName(quote.getBrokerName())
                .businessType(quote.getBusinessType())
                .niidRequired(isNiidProduct(quote.getClassOfBusinessName()))
                .policyStartDate(quote.getPolicyStartDate())
                .policyEndDate(quote.getPolicyEndDate())
                .totalSumInsured(quote.getTotalSumInsured())
                .totalPremium(quote.getTotalPremium())
                .discount(quote.getDiscount())
                .netPremium(quote.getNetPremium())
                .notes(quote.getNotes())
                .build();

        // Carry over risks from quote
        AtomicInteger order = new AtomicInteger(1);
        quote.getRisks().stream()
                .filter(r -> r.getDeletedAt() == null)
                .forEach(r -> policy.getRisks().add(PolicyRisk.builder()
                        .policy(policy)
                        .description(r.getDescription())
                        .sumInsured(r.getSumInsured())
                        .premium(r.getPremium())
                        .sectionId(r.getSectionId())
                        .sectionName(r.getSectionName())
                        .riskDetails(r.getRiskDetails())
                        .orderNo(order.getAndIncrement())
                        .build()));

        // Carry over coinsurance participants
        quote.getCoinsuranceParticipants().stream()
                .filter(p -> p.getDeletedAt() == null)
                .forEach(p -> policy.getCoinsuranceParticipants().add(
                        PolicyCoinsuranceParticipant.builder()
                                .policy(policy)
                                .insuranceCompanyId(p.getInsuranceCompanyId())
                                .insuranceCompanyName(p.getInsuranceCompanyName())
                                .sharePercentage(p.getSharePercentage())
                                .build()));

        Policy saved = repository.save(policy);
        quoteService.markConverted(quoteId);
        auditService.log("Policy", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    // ─── Direct create ────────────────────────────────────────────────────

    @Transactional
    public PolicyResponse create(PolicyRequest request) {
        Customer customer = customerService.findOrThrow(request.getCustomerId());
        Product product = productRepository.findById(request.getProductId())
                .filter(p -> p.getDeletedAt() == null && p.isActive())
                .orElseThrow(() -> new ResourceNotFoundException("Product", request.getProductId()));

        String brokerName = null;
        if (request.getBrokerId() != null) {
            Broker broker = brokerRepository.findById(request.getBrokerId())
                    .filter(b -> b.getDeletedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException("Broker", request.getBrokerId()));
            brokerName = broker.getName();
        }

        validateDates(request.getPolicyStartDate(), request.getPolicyEndDate());
        BigDecimal discount = request.getDiscount() != null ? request.getDiscount() : BigDecimal.ZERO;

        Policy policy = Policy.builder()
                .customerId(customer.getId())
                .customerName(resolveCustomerName(customer))
                .productId(product.getId())
                .productName(product.getName())
                .productCode(product.getCode())
                .productRate(product.getRate())
                .classOfBusinessId(product.getClassOfBusiness().getId())
                .classOfBusinessName(product.getClassOfBusiness().getName())
                .classOfBusinessCode(product.getClassOfBusiness().getCode())
                .brokerId(request.getBrokerId())
                .brokerName(brokerName)
                .businessType(request.getBusinessType())
                .niidRequired(request.isNiidRequired())
                .policyStartDate(request.getPolicyStartDate())
                .policyEndDate(request.getPolicyEndDate())
                .discount(discount)
                .notes(request.getNotes())
                .build();

        applyRisks(policy, request.getRisks(), product);
        applyCoinsuranceParticipants(policy, request.getCoinsuranceParticipants());
        recalculateTotals(policy, discount);
        validateCoinsuranceShares(policy);

        Policy saved = repository.save(policy);
        auditService.log("Policy", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    // ─── Update ───────────────────────────────────────────────────────────

    @Transactional
    public PolicyResponse update(UUID id, PolicyUpdateRequest request) {
        Policy policy = findOrThrow(id);
        requireDraftStatus(policy, "update");

        if (request.getBrokerId() != null) {
            Broker broker = brokerRepository.findById(request.getBrokerId())
                    .filter(b -> b.getDeletedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException("Broker", request.getBrokerId()));
            policy.setBrokerId(request.getBrokerId());
            policy.setBrokerName(broker.getName());
        }
        if (request.getBusinessType() != null)    policy.setBusinessType(request.getBusinessType());
        if (request.getNiidRequired() != null)    policy.setNiidRequired(request.getNiidRequired());
        if (request.getPolicyStartDate() != null) policy.setPolicyStartDate(request.getPolicyStartDate());
        if (request.getPolicyEndDate() != null)   policy.setPolicyEndDate(request.getPolicyEndDate());
        if (request.getNotes() != null)           policy.setNotes(request.getNotes());

        if (request.getPolicyStartDate() != null || request.getPolicyEndDate() != null) {
            validateDates(policy.getPolicyStartDate(), policy.getPolicyEndDate());
        }

        BigDecimal discount = request.getDiscount() != null ? request.getDiscount() : policy.getDiscount();

        if (request.getRisks() != null && !request.getRisks().isEmpty()) {
            Product product = productRepository.findById(policy.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", policy.getProductId()));
            applyRisks(policy, request.getRisks(), product);
        }
        if (request.getCoinsuranceParticipants() != null) {
            applyCoinsuranceParticipants(policy, request.getCoinsuranceParticipants());
        }

        recalculateTotals(policy, discount);
        validateCoinsuranceShares(policy);

        Policy saved = repository.save(policy);
        auditService.log("Policy", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    // ─── Submit for approval ──────────────────────────────────────────────

    @Transactional
    public PolicyResponse submit(UUID id) {
        Policy policy = findOrThrow(id);
        requireDraftStatus(policy, "submit");

        String workflowId = "policy-approval-" + id;
        ApprovalWorkflow workflow = workflowClient.newWorkflowStub(
                ApprovalWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TemporalQueues.APPROVAL_QUEUE)
                        .build());

        WorkflowClient.start(workflow::runApproval, ApprovalRequest.builder()
                .entityType("POLICY")
                .entityId(id.toString())
                .tenantId(TenantContext.getTenantId())
                .initiatedBy(currentUserId())
                .amount(policy.getNetPremium())
                .currency("NGN")
                .build());

        policy.setStatus(PolicyStatus.PENDING_APPROVAL);
        policy.setWorkflowId(workflowId);

        Policy saved = repository.save(policy);
        auditService.log("Policy", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    // ─── Approve ──────────────────────────────────────────────────────────

    @Transactional
    public PolicyResponse approve(UUID id, PolicyApprovalRequest request) {
        Policy policy = findOrThrow(id);
        if (policy.getStatus() != PolicyStatus.PENDING_APPROVAL) {
            throw new BusinessRuleException("INVALID_POLICY_STATUS",
                    "Cannot approve a policy with status: " + policy.getStatus());
        }

        // Generate policy number atomically using the configured format
        String policyNumber = policyNumberFormatService.generateNext(policy.getProductId());

        policy.setPolicyNumber(policyNumber);
        policy.setStatus(PolicyStatus.ACTIVE);
        policy.setApprovedBy(currentUserId());
        policy.setApprovedAt(Instant.now());
        policy.setNaicomUid("PENDING");

        signalWorkflow(policy.getWorkflowId(), "approve",
                currentUserId(), request != null ? request.getComments() : null);

        Policy saved = repository.save(policy);

        // Generate and store policy certificate PDF
        String docPath = documentGenerationService.generatePolicyDocument(new PolicyDocumentContext(
                saved.getId(), saved.getPolicyNumber(),
                saved.getProductId(), saved.getProductName(),
                saved.getClassOfBusinessId(), saved.getClassOfBusinessName(),
                saved.getCustomerName(),
                saved.getPolicyStartDate(), saved.getPolicyEndDate(),
                saved.getTotalSumInsured(), saved.getNetPremium(), "NGN",
                saved.getApprovedBy(), saved.getApprovedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                saved.getNotes()));
        if (docPath != null) {
            saved.setPolicyDocumentPath(docPath);
            saved = repository.save(saved);
        }

        // Start NAICOM upload async workflow
        startNaicomWorkflow(saved);

        // Start NIID upload async workflow for motor/marine
        if (saved.isNiidRequired()) {
            startNiidWorkflow(saved);
        }

        eventPublisher.publishEvent(new PolicyApprovedEvent(
                saved.getId(), saved.getPolicyNumber(),
                saved.getCustomerId(), saved.getCustomerName(),
                saved.getBrokerId(), saved.getBrokerName(),
                saved.getProductName(), saved.getNetPremium(),
                "NGN", saved.getPolicyEndDate(),
                saved.getProductId(), saved.getClassOfBusinessId(),
                saved.getTotalSumInsured(), saved.getPolicyStartDate()));

        auditService.log("Policy", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    // ─── Reject ───────────────────────────────────────────────────────────

    @Transactional
    public PolicyResponse reject(UUID id, PolicyApprovalRequest request) {
        Policy policy = findOrThrow(id);
        if (policy.getStatus() != PolicyStatus.PENDING_APPROVAL) {
            throw new BusinessRuleException("INVALID_POLICY_STATUS",
                    "Cannot reject a policy with status: " + policy.getStatus());
        }

        policy.setStatus(PolicyStatus.REJECTED);
        policy.setRejectedBy(currentUserId());
        policy.setRejectedAt(Instant.now());
        policy.setRejectionReason(request != null ? request.getComments() : null);

        signalWorkflow(policy.getWorkflowId(), "reject",
                currentUserId(), request != null ? request.getComments() : null);

        Policy saved = repository.save(policy);
        auditService.log("Policy", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    // ─── Cancel ───────────────────────────────────────────────────────────

    @Transactional
    public PolicyResponse cancel(UUID id, PolicyCancellationRequest request) {
        Policy policy = findOrThrow(id);
        if (policy.getStatus() != PolicyStatus.ACTIVE && policy.getStatus() != PolicyStatus.REINSTATED) {
            throw new BusinessRuleException("INVALID_POLICY_STATUS",
                    "Cannot cancel a policy with status: " + policy.getStatus());
        }

        policy.setStatus(PolicyStatus.CANCELLED);
        policy.setCancelledBy(currentUserId());
        policy.setCancelledAt(Instant.now());
        policy.setCancellationReason(request.getReason());

        Policy saved = repository.save(policy);
        auditService.log("Policy", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    // ─── Reinstate ────────────────────────────────────────────────────────

    @Transactional
    public PolicyResponse reinstate(UUID id) {
        Policy policy = findOrThrow(id);
        if (policy.getStatus() != PolicyStatus.CANCELLED) {
            throw new BusinessRuleException("INVALID_POLICY_STATUS",
                    "Cannot reinstate a policy with status: " + policy.getStatus());
        }

        policy.setStatus(PolicyStatus.REINSTATED);
        policy.setCancelledBy(null);
        policy.setCancelledAt(null);
        policy.setCancellationReason(null);

        Policy saved = repository.save(policy);
        auditService.log("Policy", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    // ─── NAICOM manual retrigger ──────────────────────────────────────────

    @Transactional
    public PolicyResponse triggerNaicomUpload(UUID id) {
        Policy policy = findOrThrow(id);
        if (policy.getStatus() != PolicyStatus.ACTIVE && policy.getStatus() != PolicyStatus.REINSTATED) {
            throw new BusinessRuleException("INVALID_POLICY_STATUS",
                    "NAICOM upload requires an active policy: " + id);
        }
        startNaicomWorkflow(policy);
        auditService.log("Policy", id.toString(), AuditAction.UPDATE, null, policy);
        return toResponse(policy);
    }

    // ─── Temporal helpers ─────────────────────────────────────────────────

    private void startNaicomWorkflow(Policy policy) {
        String naicomWorkflowId = "naicom-upload-" + policy.getId();
        try {
            NaicomUploadWorkflow workflow = workflowClient.newWorkflowStub(
                    NaicomUploadWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(naicomWorkflowId)
                            .setTaskQueue(TemporalQueues.NAICOM_QUEUE)
                            .build());
            WorkflowClient.start(workflow::uploadPolicy,
                    policy.getId().toString(), TenantContext.getTenantId());
        } catch (Exception e) {
            log.warn("Could not start NAICOM upload workflow for policy {}: {}", policy.getId(), e.getMessage());
        }
    }

    private void startNiidWorkflow(Policy policy) {
        String niidWorkflowId = "niid-upload-" + policy.getId();
        try {
            NiidUploadWorkflow workflow = workflowClient.newWorkflowStub(
                    NiidUploadWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(niidWorkflowId)
                            .setTaskQueue(TemporalQueues.NIID_QUEUE)
                            .build());
            WorkflowClient.start(workflow::uploadPolicy,
                    policy.getId().toString(), TenantContext.getTenantId());
        } catch (Exception e) {
            log.warn("Could not start NIID upload workflow for policy {}: {}", policy.getId(), e.getMessage());
        }
    }

    private void signalWorkflow(String workflowId, String signal, String userId, String comments) {
        if (workflowId == null) return;
        try {
            ApprovalWorkflow workflow = workflowClient.newWorkflowStub(
                    ApprovalWorkflow.class, workflowId);
            if ("approve".equals(signal)) {
                workflow.approve(userId, comments);
            } else {
                workflow.reject(userId, comments);
            }
        } catch (Exception e) {
            log.warn("Could not signal Temporal workflow {}: {}", workflowId, e.getMessage());
        }
    }

    // ─── Business logic helpers ───────────────────────────────────────────

    private void applyRisks(Policy policy, List<PolicyRiskRequest> requests, Product product) {
        policy.getRisks().clear();
        AtomicInteger order = new AtomicInteger(1);
        requests.forEach(r -> {
            String sectionName = null;
            if (r.getSectionId() != null) {
                sectionName = product.getSections().stream()
                        .filter(s -> s.getId().equals(r.getSectionId()) && s.getDeletedAt() == null)
                        .findFirst()
                        .map(ProductSection::getName)
                        .orElse(null);
            }
            BigDecimal premium = r.getSumInsured().multiply(product.getRate());
            policy.getRisks().add(PolicyRisk.builder()
                    .policy(policy)
                    .description(r.getDescription())
                    .sumInsured(r.getSumInsured())
                    .premium(premium)
                    .sectionId(r.getSectionId())
                    .sectionName(sectionName)
                    .riskDetails(r.getRiskDetails())
                    .vehicleRegNumber(r.getVehicleRegNumber())
                    .orderNo(order.getAndIncrement())
                    .build());
        });
    }

    private void applyCoinsuranceParticipants(Policy policy,
            List<PolicyCoinsuranceParticipantRequest> requests) {
        policy.getCoinsuranceParticipants().clear();
        if (requests == null || requests.isEmpty()) return;
        requests.forEach(r -> {
            InsuranceCompany company = insuranceCompanyRepository.findById(r.getInsuranceCompanyId())
                    .filter(c -> c.getDeletedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "InsuranceCompany", r.getInsuranceCompanyId()));
            policy.getCoinsuranceParticipants().add(PolicyCoinsuranceParticipant.builder()
                    .policy(policy)
                    .insuranceCompanyId(company.getId())
                    .insuranceCompanyName(company.getName())
                    .sharePercentage(r.getSharePercentage())
                    .build());
        });
    }

    private void recalculateTotals(Policy policy, BigDecimal discount) {
        BigDecimal totalSumInsured = policy.getRisks().stream()
                .map(PolicyRisk::getSumInsured)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPremium = policy.getRisks().stream()
                .map(PolicyRisk::getPremium)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal effectiveDiscount = discount.min(totalPremium);

        policy.setTotalSumInsured(totalSumInsured);
        policy.setTotalPremium(totalPremium);
        policy.setDiscount(effectiveDiscount);
        policy.setNetPremium(totalPremium.subtract(effectiveDiscount));
    }

    private void validateCoinsuranceShares(Policy policy) {
        if (policy.getBusinessType() != BusinessType.DIRECT_WITH_COINSURANCE) return;
        if (policy.getCoinsuranceParticipants().isEmpty()) {
            throw new BusinessRuleException("COINSURANCE_PARTICIPANTS_REQUIRED",
                    "Coinsurance participants required for DIRECT_WITH_COINSURANCE");
        }
        BigDecimal total = policy.getCoinsuranceParticipants().stream()
                .map(PolicyCoinsuranceParticipant::getSharePercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(new BigDecimal("100")) >= 0) {
            throw new BusinessRuleException("COINSURANCE_SHARES_EXCEED_100",
                    "Coinsurance participant shares must be less than 100%");
        }
    }

    private void requireDraftStatus(Policy policy, String action) {
        if (policy.getStatus() != PolicyStatus.DRAFT) {
            throw new BusinessRuleException("INVALID_POLICY_STATUS",
                    "Cannot " + action + " a policy with status: " + policy.getStatus());
        }
    }

    private void validateDates(java.time.LocalDate start, java.time.LocalDate end) {
        if (end != null && start != null && !end.isAfter(start)) {
            throw new BusinessRuleException("INVALID_POLICY_DATES",
                    "Policy end date must be after start date");
        }
    }

    private String resolveCustomerName(Customer customer) {
        if (customer.getCustomerType() == CustomerType.INDIVIDUAL) {
            return customer.getFirstName() + " " + customer.getLastName();
        }
        return customer.getCompanyName();
    }

    private String resolveClassCode(UUID classOfBusinessId) {
        return classOfBusinessRepository.findById(classOfBusinessId)
                .filter(c -> c.getDeletedAt() == null)
                .map(com.nubeero.cia.setup.product.ClassOfBusiness::getCode)
                .orElse("");
    }

    private boolean isNiidProduct(String classOfBusinessName) {
        if (classOfBusinessName == null) return false;
        String lower = classOfBusinessName.toLowerCase();
        return lower.contains("motor") || lower.contains("marine");
    }

    Policy findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", id));
    }

    private String currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return "system";
    }

    // ─── Mapping ──────────────────────────────────────────────────────────

    private PolicySummaryResponse toSummary(Policy p) {
        return PolicySummaryResponse.builder()
                .id(p.getId()).policyNumber(p.getPolicyNumber()).status(p.getStatus())
                .customerId(p.getCustomerId()).customerName(p.getCustomerName())
                .productName(p.getProductName()).classOfBusinessName(p.getClassOfBusinessName())
                .brokerName(p.getBrokerName()).businessType(p.getBusinessType())
                .policyStartDate(p.getPolicyStartDate()).policyEndDate(p.getPolicyEndDate())
                .netPremium(p.getNetPremium()).naicomUid(p.getNaicomUid()).createdAt(p.getCreatedAt())
                .build();
    }

    PolicyResponse toResponse(Policy p) {
        List<PolicyRiskResponse> risks = p.getRisks() == null ? List.of() :
                p.getRisks().stream()
                        .filter(r -> r.getDeletedAt() == null)
                        .map(r -> PolicyRiskResponse.builder()
                                .id(r.getId()).description(r.getDescription())
                                .sumInsured(r.getSumInsured()).premium(r.getPremium())
                                .sectionId(r.getSectionId()).sectionName(r.getSectionName())
                                .riskDetails(r.getRiskDetails())
                                .vehicleRegNumber(r.getVehicleRegNumber()).orderNo(r.getOrderNo())
                                .build())
                        .toList();

        List<PolicyCoinsuranceParticipantResponse> participants =
                p.getCoinsuranceParticipants() == null ? List.of() :
                        p.getCoinsuranceParticipants().stream()
                                .filter(c -> c.getDeletedAt() == null)
                                .map(c -> PolicyCoinsuranceParticipantResponse.builder()
                                        .id(c.getId())
                                        .insuranceCompanyId(c.getInsuranceCompanyId())
                                        .insuranceCompanyName(c.getInsuranceCompanyName())
                                        .sharePercentage(c.getSharePercentage())
                                        .build())
                                .toList();

        return PolicyResponse.builder()
                .id(p.getId()).policyNumber(p.getPolicyNumber()).status(p.getStatus())
                .quoteId(p.getQuoteId()).quoteNumber(p.getQuoteNumber())
                .customerId(p.getCustomerId()).customerName(p.getCustomerName())
                .productId(p.getProductId()).productName(p.getProductName())
                .productCode(p.getProductCode()).productRate(p.getProductRate())
                .classOfBusinessId(p.getClassOfBusinessId()).classOfBusinessName(p.getClassOfBusinessName())
                .classOfBusinessCode(p.getClassOfBusinessCode())
                .brokerId(p.getBrokerId()).brokerName(p.getBrokerName())
                .businessType(p.getBusinessType()).niidRequired(p.isNiidRequired())
                .policyStartDate(p.getPolicyStartDate()).policyEndDate(p.getPolicyEndDate())
                .totalSumInsured(p.getTotalSumInsured()).totalPremium(p.getTotalPremium())
                .discount(p.getDiscount()).netPremium(p.getNetPremium())
                .notes(p.getNotes()).workflowId(p.getWorkflowId())
                .approvedBy(p.getApprovedBy()).approvedAt(p.getApprovedAt())
                .rejectedBy(p.getRejectedBy()).rejectedAt(p.getRejectedAt())
                .rejectionReason(p.getRejectionReason())
                .cancelledBy(p.getCancelledBy()).cancelledAt(p.getCancelledAt())
                .cancellationReason(p.getCancellationReason())
                .naicomUid(p.getNaicomUid()).naicomUploadedAt(p.getNaicomUploadedAt())
                .naicomCertificatePath(p.getNaicomCertificatePath())
                .niidRef(p.getNiidRef()).niidUploadedAt(p.getNiidUploadedAt())
                .policyDocumentPath(p.getPolicyDocumentPath())
                .risks(risks).coinsuranceParticipants(participants)
                .createdAt(p.getCreatedAt()).updatedAt(p.getUpdatedAt())
                .build();
    }
}
