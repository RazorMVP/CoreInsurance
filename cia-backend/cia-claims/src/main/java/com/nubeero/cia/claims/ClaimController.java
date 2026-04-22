package com.nubeero.cia.claims;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.claims.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/claims")
@RequiredArgsConstructor
public class ClaimController {

    private final ClaimService service;

    @GetMapping
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    public ApiResponse<Page<ClaimResponse>> list(
            @RequestParam(required = false) UUID policyId,
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) UUID customerId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(
                service.list(policyId, status, customerId, pageable).map(this::toResponse));
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    public ApiResponse<Page<ClaimResponse>> search(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.search(q, pageable).map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    public ApiResponse<ClaimResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CLAIMS_CREATE')")
    public ApiResponse<ClaimResponse> register(
            @Valid @RequestBody RegisterClaimRequest req) {
        return ApiResponse.success(toResponse(service.register(req)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    public ApiResponse<ClaimResponse> update(
            @PathVariable UUID id,
            @RequestBody UpdateClaimRequest req) {
        return ApiResponse.success(toResponse(service.updateDetails(id, req)));
    }

    @PostMapping("/{id}/assign-surveyor")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    public ApiResponse<ClaimResponse> assignSurveyor(
            @PathVariable UUID id,
            @Valid @RequestBody AssignSurveyorRequest req) {
        return ApiResponse.success(toResponse(service.assignSurveyor(id, req.surveyorId())));
    }

    @PostMapping("/{id}/reserve")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    public ApiResponse<ClaimResponse> setReserve(
            @PathVariable UUID id,
            @Valid @RequestBody SetReserveRequest req) {
        return ApiResponse.success(toResponse(service.setReserve(id, req)));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('CLAIMS_CREATE')")
    public ApiResponse<ClaimResponse> submit(
            @PathVariable UUID id,
            @RequestBody(required = false) SubmitClaimRequest req) {
        return ApiResponse.success(toResponse(service.submitForApproval(id,
                req != null ? req : new SubmitClaimRequest(null))));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    public ApiResponse<ClaimResponse> approve(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.approve(id)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    public ApiResponse<ClaimResponse> reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectClaimRequest req) {
        return ApiResponse.success(toResponse(service.reject(id, req.reason())));
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    public ApiResponse<ClaimResponse> withdraw(
            @PathVariable UUID id,
            @Valid @RequestBody WithdrawClaimRequest req) {
        return ApiResponse.success(toResponse(service.withdraw(id, req.reason())));
    }

    @PostMapping("/{id}/settle")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    public ApiResponse<ClaimResponse> settle(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.markSettled(id)));
    }

    @GetMapping("/{id}/reserves")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    public ApiResponse<Page<ClaimReserveResponse>> reserves(
            @PathVariable UUID id,
            @PageableDefault(size = 20) Pageable pageable) {
        Claim claim = service.findOrThrow(id);
        return ApiResponse.success(
                claim.getReserves().stream()
                        .filter(r -> r.getDeletedAt() == null)
                        .map(r -> new ClaimReserveResponse(
                                r.getId(), r.getAmount(), r.getPreviousAmount(),
                                r.getReason(), r.getCreatedBy(), r.getCreatedAt()))
                        .collect(java.util.stream.Collectors.collectingAndThen(
                                java.util.stream.Collectors.toList(),
                                list -> new org.springframework.data.domain.PageImpl<>(
                                        list, pageable, list.size()))));
    }

    // ─── Mapping ──────────────────────────────────────────────────────────

    private ClaimResponse toResponse(Claim c) {
        return new ClaimResponse(
                c.getId(), c.getClaimNumber(), c.getStatus(),
                c.getPolicyId(), c.getPolicyNumber(),
                c.getPolicyStartDate(), c.getPolicyEndDate(),
                c.getCustomerId(), c.getCustomerName(),
                c.getProductName(), c.getClassOfBusinessName(),
                c.getBrokerId(), c.getBrokerName(),
                c.getIncidentDate(), c.getReportedDate(),
                c.getLossLocation(), c.getDescription(),
                c.getEstimatedLoss(), c.getReserveAmount(), c.getApprovedAmount(),
                c.getCurrencyCode(),
                c.getSurveyorId(), c.getSurveyorName(), c.getSurveyorAssignedAt(),
                c.getApprovedBy(), c.getApprovedAt(),
                c.getRejectedBy(), c.getRejectedAt(), c.getRejectionReason(),
                c.getWithdrawnBy(), c.getWithdrawnAt(), c.getWithdrawalReason(),
                c.getSettledAt(), c.getNotes(), c.getCreatedAt()
        );
    }
}
