package com.nubeero.cia.quotation;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.customer.Customer;
import com.nubeero.cia.customer.CustomerService;
import com.nubeero.cia.customer.CustomerType;
import com.nubeero.cia.quotation.dto.*;
import com.nubeero.cia.setup.org.Broker;
import com.nubeero.cia.setup.org.BrokerRepository;
import com.nubeero.cia.setup.org.InsuranceCompany;
import com.nubeero.cia.setup.org.InsuranceCompanyRepository;
import com.nubeero.cia.setup.product.Product;
import com.nubeero.cia.setup.product.ProductRepository;
import com.nubeero.cia.setup.product.ProductSection;
import com.nubeero.cia.workflow.TemporalQueues;
import com.nubeero.cia.workflow.approval.ApprovalRequest;
import com.nubeero.cia.workflow.approval.ApprovalWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuoteService {

    private final QuoteRepository repository;
    private final QuoteNumberService quoteNumberService;
    private final CustomerService customerService;
    private final ProductRepository productRepository;
    private final BrokerRepository brokerRepository;
    private final InsuranceCompanyRepository insuranceCompanyRepository;
    private final AuditService auditService;
    private final WorkflowClient workflowClient;

    // ─── Queries ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<QuoteSummaryResponse> list(QuoteStatus status, UUID customerId, Pageable pageable) {
        Page<Quote> page;
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
    public Page<QuoteSummaryResponse> search(String query, Pageable pageable) {
        return repository.search(query, pageable).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public QuoteResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    // ─── Create ───────────────────────────────────────────────────────────

    @Transactional
    public QuoteResponse create(QuoteRequest request) {
        // Resolve cross-module references
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

        String quoteNumber = quoteNumberService.nextQuoteNumber();
        BigDecimal discount = request.getDiscount() != null ? request.getDiscount() : BigDecimal.ZERO;

        Quote quote = Quote.builder()
                .quoteNumber(quoteNumber)
                .customerId(customer.getId())
                .customerName(resolveCustomerName(customer))
                .productId(product.getId())
                .productName(product.getName())
                .productCode(product.getCode())
                .productRate(product.getRate())
                .classOfBusinessId(product.getClassOfBusiness().getId())
                .classOfBusinessName(product.getClassOfBusiness().getName())
                .brokerId(request.getBrokerId())
                .brokerName(brokerName)
                .businessType(request.getBusinessType())
                .policyStartDate(request.getPolicyStartDate())
                .policyEndDate(request.getPolicyEndDate())
                .discount(discount)
                .notes(request.getNotes())
                .expiresAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .build();

        applyRisks(quote, request.getRisks(), product);
        applyCoinsuranceParticipants(quote, request.getCoinsuranceParticipants());
        recalculateTotals(quote, discount);
        validateCoinsuranceShares(quote);

        Quote saved = repository.save(quote);
        auditService.log("Quote", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    // ─── Update ───────────────────────────────────────────────────────────

    @Transactional
    public QuoteResponse update(UUID id, QuoteUpdateRequest request) {
        Quote quote = findOrThrow(id);
        requireStatus(quote, QuoteStatus.DRAFT, "update");

        if (request.getBrokerId() != null) {
            Broker broker = brokerRepository.findById(request.getBrokerId())
                    .filter(b -> b.getDeletedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException("Broker", request.getBrokerId()));
            quote.setBrokerId(request.getBrokerId());
            quote.setBrokerName(broker.getName());
        }
        if (request.getBusinessType() != null)    quote.setBusinessType(request.getBusinessType());
        if (request.getPolicyStartDate() != null) quote.setPolicyStartDate(request.getPolicyStartDate());
        if (request.getPolicyEndDate() != null)   quote.setPolicyEndDate(request.getPolicyEndDate());
        if (request.getNotes() != null)           quote.setNotes(request.getNotes());

        if (request.getPolicyStartDate() != null || request.getPolicyEndDate() != null) {
            validateDates(quote.getPolicyStartDate(), quote.getPolicyEndDate());
        }

        BigDecimal discount = request.getDiscount() != null ? request.getDiscount() : quote.getDiscount();

        if (request.getRisks() != null && !request.getRisks().isEmpty()) {
            Product product = productRepository.findById(quote.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", quote.getProductId()));
            applyRisks(quote, request.getRisks(), product);
        }

        if (request.getCoinsuranceParticipants() != null) {
            applyCoinsuranceParticipants(quote, request.getCoinsuranceParticipants());
        }

        recalculateTotals(quote, discount);
        validateCoinsuranceShares(quote);

        Quote saved = repository.save(quote);
        auditService.log("Quote", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    // ─── Submit for approval ──────────────────────────────────────────────

    @Transactional
    public QuoteResponse submit(UUID id) {
        Quote quote = findOrThrow(id);
        requireStatus(quote, QuoteStatus.DRAFT, "submit");

        String workflowId = "quote-approval-" + id;
        ApprovalWorkflow workflow = workflowClient.newWorkflowStub(
                ApprovalWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TemporalQueues.APPROVAL_QUEUE)
                        .build());

        WorkflowClient.start(workflow::runApproval, ApprovalRequest.builder()
                .entityType("QUOTE")
                .entityId(id.toString())
                .tenantId(TenantContext.getTenantId())
                .initiatedBy(currentUserId())
                .amount(quote.getNetPremium())
                .currency("NGN")
                .build());

        quote.setStatus(QuoteStatus.PENDING_APPROVAL);
        quote.setWorkflowId(workflowId);

        Quote saved = repository.save(quote);
        auditService.log("Quote", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    // ─── Approve ──────────────────────────────────────────────────────────

    @Transactional
    public QuoteResponse approve(UUID id, QuoteApprovalRequest request) {
        Quote quote = findOrThrow(id);
        requireStatus(quote, QuoteStatus.PENDING_APPROVAL, "approve");

        quote.setStatus(QuoteStatus.APPROVED);
        quote.setApprovedBy(currentUserId());
        quote.setApprovedAt(Instant.now());

        if (quote.getWorkflowId() != null) {
            try {
                ApprovalWorkflow workflow = workflowClient.newWorkflowStub(
                        ApprovalWorkflow.class, quote.getWorkflowId());
                workflow.approve(currentUserId(), request != null ? request.getComments() : null);
            } catch (Exception e) {
                log.warn("Could not signal Temporal workflow {} for quote {}: {}",
                        quote.getWorkflowId(), id, e.getMessage());
            }
        }

        Quote saved = repository.save(quote);
        auditService.log("Quote", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    // ─── Reject ───────────────────────────────────────────────────────────

    @Transactional
    public QuoteResponse reject(UUID id, QuoteApprovalRequest request) {
        Quote quote = findOrThrow(id);
        requireStatus(quote, QuoteStatus.PENDING_APPROVAL, "reject");

        quote.setStatus(QuoteStatus.REJECTED);
        quote.setRejectedBy(currentUserId());
        quote.setRejectedAt(Instant.now());
        quote.setRejectionReason(request != null ? request.getComments() : null);

        if (quote.getWorkflowId() != null) {
            try {
                ApprovalWorkflow workflow = workflowClient.newWorkflowStub(
                        ApprovalWorkflow.class, quote.getWorkflowId());
                workflow.reject(currentUserId(), request != null ? request.getComments() : null);
            } catch (Exception e) {
                log.warn("Could not signal Temporal workflow {} for quote {}: {}",
                        quote.getWorkflowId(), id, e.getMessage());
            }
        }

        Quote saved = repository.save(quote);
        auditService.log("Quote", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    // ─── Convert to policy ────────────────────────────────────────────────

    @Transactional
    public QuoteResponse markConverted(UUID id) {
        Quote quote = findOrThrow(id);
        if (quote.getStatus() != QuoteStatus.APPROVED) {
            throw new BusinessRuleException("QUOTE_NOT_APPROVED",
                    "Only approved quotes can be converted to a policy: " + id);
        }
        quote.setStatus(QuoteStatus.CONVERTED);
        Quote saved = repository.save(quote);
        auditService.log("Quote", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    // ─── Internal helpers ─────────────────────────────────────────────────

    private void applyRisks(Quote quote, List<QuoteRiskRequest> requests, Product product) {
        quote.getRisks().clear();
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
            quote.getRisks().add(QuoteRisk.builder()
                    .quote(quote)
                    .description(r.getDescription())
                    .sumInsured(r.getSumInsured())
                    .premium(premium)
                    .sectionId(r.getSectionId())
                    .sectionName(sectionName)
                    .riskDetails(r.getRiskDetails())
                    .orderNo(order.getAndIncrement())
                    .build());
        });
    }

    private void applyCoinsuranceParticipants(Quote quote,
            List<QuoteCoinsuranceParticipantRequest> requests) {
        quote.getCoinsuranceParticipants().clear();
        if (requests == null || requests.isEmpty()) return;
        requests.forEach(r -> {
            InsuranceCompany company = insuranceCompanyRepository.findById(r.getInsuranceCompanyId())
                    .filter(c -> c.getDeletedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "InsuranceCompany", r.getInsuranceCompanyId()));
            quote.getCoinsuranceParticipants().add(QuoteCoinsuranceParticipant.builder()
                    .quote(quote)
                    .insuranceCompanyId(company.getId())
                    .insuranceCompanyName(company.getName())
                    .sharePercentage(r.getSharePercentage())
                    .build());
        });
    }

    private void recalculateTotals(Quote quote, BigDecimal discount) {
        BigDecimal totalSumInsured = quote.getRisks().stream()
                .map(QuoteRisk::getSumInsured)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPremium = quote.getRisks().stream()
                .map(QuoteRisk::getPremium)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal effectiveDiscount = discount.min(totalPremium);

        quote.setTotalSumInsured(totalSumInsured);
        quote.setTotalPremium(totalPremium);
        quote.setDiscount(effectiveDiscount);
        quote.setNetPremium(totalPremium.subtract(effectiveDiscount));
    }

    private void validateCoinsuranceShares(Quote quote) {
        if (quote.getBusinessType() != BusinessType.DIRECT_WITH_COINSURANCE) return;
        if (quote.getCoinsuranceParticipants().isEmpty()) {
            throw new BusinessRuleException("COINSURANCE_PARTICIPANTS_REQUIRED",
                    "Coinsurance participants are required for DIRECT_WITH_COINSURANCE business type");
        }
        BigDecimal totalShares = quote.getCoinsuranceParticipants().stream()
                .map(QuoteCoinsuranceParticipant::getSharePercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalShares.compareTo(new BigDecimal("100")) >= 0) {
            throw new BusinessRuleException("COINSURANCE_SHARES_EXCEED_100",
                    "Coinsurance participant shares must be less than 100% (retention must be positive)");
        }
    }

    private void validateDates(java.time.LocalDate start, java.time.LocalDate end) {
        if (end != null && start != null && !end.isAfter(start)) {
            throw new BusinessRuleException("INVALID_POLICY_DATES",
                    "Policy end date must be after start date");
        }
    }

    private void requireStatus(Quote quote, QuoteStatus required, String action) {
        if (quote.getStatus() != required) {
            throw new BusinessRuleException("INVALID_QUOTE_STATUS",
                    "Cannot " + action + " a quote with status: " + quote.getStatus());
        }
    }

    private String resolveCustomerName(Customer customer) {
        if (customer.getCustomerType() == CustomerType.INDIVIDUAL) {
            return customer.getFirstName() + " " + customer.getLastName();
        }
        return customer.getCompanyName();
    }

    public Quote findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(q -> q.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Quote", id));
    }

    private String currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return "system";
    }

    // ─── Mapping ──────────────────────────────────────────────────────────

    private QuoteSummaryResponse toSummary(Quote q) {
        return QuoteSummaryResponse.builder()
                .id(q.getId()).quoteNumber(q.getQuoteNumber()).status(q.getStatus())
                .customerId(q.getCustomerId()).customerName(q.getCustomerName())
                .productName(q.getProductName()).classOfBusinessName(q.getClassOfBusinessName())
                .brokerName(q.getBrokerName()).businessType(q.getBusinessType())
                .policyStartDate(q.getPolicyStartDate()).policyEndDate(q.getPolicyEndDate())
                .netPremium(q.getNetPremium()).expiresAt(q.getExpiresAt()).createdAt(q.getCreatedAt())
                .build();
    }

    QuoteResponse toResponse(Quote q) {
        List<QuoteRiskResponse> risks = q.getRisks() == null ? List.of() :
                q.getRisks().stream()
                        .filter(r -> r.getDeletedAt() == null)
                        .map(r -> QuoteRiskResponse.builder()
                                .id(r.getId()).description(r.getDescription())
                                .sumInsured(r.getSumInsured()).premium(r.getPremium())
                                .sectionId(r.getSectionId()).sectionName(r.getSectionName())
                                .riskDetails(r.getRiskDetails()).orderNo(r.getOrderNo())
                                .build())
                        .toList();

        List<QuoteCoinsuranceParticipantResponse> participants =
                q.getCoinsuranceParticipants() == null ? List.of() :
                        q.getCoinsuranceParticipants().stream()
                                .filter(p -> p.getDeletedAt() == null)
                                .map(p -> QuoteCoinsuranceParticipantResponse.builder()
                                        .id(p.getId())
                                        .insuranceCompanyId(p.getInsuranceCompanyId())
                                        .insuranceCompanyName(p.getInsuranceCompanyName())
                                        .sharePercentage(p.getSharePercentage())
                                        .build())
                                .toList();

        return QuoteResponse.builder()
                .id(q.getId()).quoteNumber(q.getQuoteNumber()).status(q.getStatus())
                .customerId(q.getCustomerId()).customerName(q.getCustomerName())
                .productId(q.getProductId()).productName(q.getProductName())
                .productCode(q.getProductCode()).productRate(q.getProductRate())
                .classOfBusinessId(q.getClassOfBusinessId()).classOfBusinessName(q.getClassOfBusinessName())
                .brokerId(q.getBrokerId()).brokerName(q.getBrokerName())
                .businessType(q.getBusinessType())
                .policyStartDate(q.getPolicyStartDate()).policyEndDate(q.getPolicyEndDate())
                .totalSumInsured(q.getTotalSumInsured()).totalPremium(q.getTotalPremium())
                .discount(q.getDiscount()).netPremium(q.getNetPremium())
                .notes(q.getNotes()).workflowId(q.getWorkflowId())
                .approvedBy(q.getApprovedBy()).approvedAt(q.getApprovedAt())
                .rejectedBy(q.getRejectedBy()).rejectedAt(q.getRejectedAt())
                .rejectionReason(q.getRejectionReason()).expiresAt(q.getExpiresAt())
                .risks(risks).coinsuranceParticipants(participants)
                .createdAt(q.getCreatedAt()).updatedAt(q.getUpdatedAt())
                .build();
    }
}
