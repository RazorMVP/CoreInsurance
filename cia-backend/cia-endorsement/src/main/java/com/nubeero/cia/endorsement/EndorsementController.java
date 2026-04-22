package com.nubeero.cia.endorsement;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.endorsement.dto.*;
import com.nubeero.cia.policy.Policy;
import com.nubeero.cia.policy.PolicyRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/endorsements")
@RequiredArgsConstructor
public class EndorsementController {

    private final EndorsementService service;
    private final PolicyRepository policyRepository;

    @GetMapping
    @PreAuthorize("hasRole('UNDERWRITING_VIEW')")
    public ApiResponse<Page<EndorsementResponse>> list(
            @RequestParam(required = false) UUID policyId,
            @RequestParam(required = false) EndorsementStatus status,
            @RequestParam(required = false) UUID customerId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(
                service.list(policyId, status, customerId, pageable).map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('UNDERWRITING_VIEW')")
    public ApiResponse<EndorsementResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('UNDERWRITING_CREATE')")
    public ApiResponse<EndorsementResponse> create(
            @Valid @RequestBody CreateEndorsementRequest req) {
        return ApiResponse.success(toResponse(service.create(req)));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('UNDERWRITING_CREATE')")
    public ApiResponse<EndorsementResponse> submit(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.submitForApproval(id)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('UNDERWRITING_APPROVE')")
    public ApiResponse<EndorsementResponse> approve(
            @PathVariable UUID id,
            @RequestBody(required = false) ApproveEndorsementRequest req) {
        String notes = req != null ? req.notes() : null;
        return ApiResponse.success(toResponse(service.approve(id, notes)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('UNDERWRITING_APPROVE')")
    public ApiResponse<EndorsementResponse> reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectEndorsementRequest req) {
        return ApiResponse.success(toResponse(service.reject(id, req.reason())));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    public ApiResponse<EndorsementResponse> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelEndorsementRequest req) {
        return ApiResponse.success(toResponse(service.cancel(id, req.reason())));
    }

    // Preview pro-rata premium before creating an endorsement
    @GetMapping("/premium-preview")
    @PreAuthorize("hasRole('UNDERWRITING_VIEW')")
    public ApiResponse<PremiumPreviewResponse> premiumPreview(
            @RequestParam UUID policyId,
            @RequestParam String effectiveDate,
            @RequestParam BigDecimal newNetPremium) {
        Policy policy = policyRepository.findById(policyId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow();
        java.time.LocalDate effDate = java.time.LocalDate.parse(effectiveDate);
        long remaining = ChronoUnit.DAYS.between(effDate, policy.getPolicyEndDate());
        BigDecimal adjustment = service.calculatePremiumAdjustment(
                policy.getNetPremium(), newNetPremium, remaining);
        EndorsementType type = adjustment.compareTo(BigDecimal.ZERO) > 0
                ? EndorsementType.ADDITIONAL_PREMIUM
                : adjustment.compareTo(BigDecimal.ZERO) < 0
                        ? EndorsementType.RETURN_PREMIUM
                        : EndorsementType.NON_PREMIUM_BEARING;
        return ApiResponse.success(new PremiumPreviewResponse(
                policy.getNetPremium(), newNetPremium, remaining, adjustment, type));
    }

    // ─── Mapping ──────────────────────────────────────────────────────────

    private EndorsementResponse toResponse(Endorsement e) {
        List<EndorsementRiskResponse> risks = e.getRisks().stream()
                .filter(r -> r.getDeletedAt() == null)
                .map(r -> new EndorsementRiskResponse(
                        r.getId(), r.getDescription(), r.getSumInsured(), r.getPremium(),
                        r.getSectionId(), r.getSectionName(), r.getRiskDetails(),
                        r.getVehicleRegNumber(), r.getOrderNo()))
                .toList();

        return new EndorsementResponse(
                e.getId(),
                e.getEndorsementNumber(),
                e.getStatus(),
                e.getEndorsementType(),
                e.getPolicyId(),
                e.getPolicyNumber(),
                e.getCustomerId(),
                e.getCustomerName(),
                e.getProductName(),
                e.getClassOfBusinessName(),
                e.getBrokerId(),
                e.getBrokerName(),
                e.getEffectiveDate(),
                e.getPolicyEndDate(),
                e.getRemainingDays(),
                e.getOldSumInsured(),
                e.getNewSumInsured(),
                e.getOldNetPremium(),
                e.getNewNetPremium(),
                e.getPremiumAdjustment(),
                e.getCurrencyCode(),
                e.getDescription(),
                e.getNotes(),
                e.getApprovedBy(),
                e.getApprovedAt(),
                e.getRejectedBy(),
                e.getRejectedAt(),
                e.getRejectionReason(),
                e.getCreatedAt(),
                risks
        );
    }
}
