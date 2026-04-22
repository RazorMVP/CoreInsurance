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
@RequestMapping("/api/v1/claims/{claimId}/expenses")
@RequiredArgsConstructor
public class ClaimExpenseController {

    private final ClaimExpenseService service;

    @GetMapping
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    public ApiResponse<Page<ClaimExpenseResponse>> list(
            @PathVariable UUID claimId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(
                service.findByClaimId(claimId, pageable).map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    public ApiResponse<ClaimExpenseResponse> get(
            @PathVariable UUID claimId,
            @PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CLAIMS_CREATE')")
    public ApiResponse<ClaimExpenseResponse> add(
            @PathVariable UUID claimId,
            @Valid @RequestBody AddExpenseRequest req) {
        return ApiResponse.success(toResponse(service.add(claimId, req)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    public ApiResponse<ClaimExpenseResponse> approve(
            @PathVariable UUID claimId,
            @PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.approve(id)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    public ApiResponse<ClaimExpenseResponse> cancel(
            @PathVariable UUID claimId,
            @PathVariable UUID id,
            @Valid @RequestBody CancelExpenseRequest req) {
        return ApiResponse.success(toResponse(service.cancel(id, req.reason())));
    }

    private ClaimExpenseResponse toResponse(ClaimExpense e) {
        return new ClaimExpenseResponse(
                e.getId(), e.getClaim().getId(),
                e.getExpenseType(), e.getStatus(),
                e.getVendorId(), e.getVendorName(),
                e.getAmount(), e.getDescription(),
                e.getApprovedBy(), e.getApprovedAt(),
                e.getCancelledBy(), e.getCancelledAt(), e.getCancellationReason(),
                e.getCreatedAt()
        );
    }
}
