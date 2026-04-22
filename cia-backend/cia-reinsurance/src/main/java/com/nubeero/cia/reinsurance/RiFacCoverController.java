package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.reinsurance.dto.*;
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
@RequestMapping("/api/v1/ri/fac-covers")
@RequiredArgsConstructor
public class RiFacCoverController {

    private final FacCoverService service;

    @GetMapping
    @PreAuthorize("hasRole('REINSURANCE_VIEW')")
    public ApiResponse<Page<FacCoverResponse>> list(
            @RequestParam(required = false) UUID policyId,
            @RequestParam(required = false) FacCoverStatus status,
            @RequestParam(required = false) UUID reinsuranceCompanyId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(
                service.list(policyId, status, reinsuranceCompanyId, pageable).map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('REINSURANCE_VIEW')")
    public ApiResponse<FacCoverResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('REINSURANCE_CREATE')")
    public ApiResponse<FacCoverResponse> create(@Valid @RequestBody CreateFacCoverRequest req) {
        return ApiResponse.success(toResponse(service.create(req)));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('REINSURANCE_APPROVE')")
    public ApiResponse<FacCoverResponse> confirm(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.confirm(id)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    public ApiResponse<FacCoverResponse> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelFacCoverRequest req) {
        return ApiResponse.success(toResponse(service.cancel(id, req.reason())));
    }

    // ─── Mapping ──────────────────────────────────────────────────────────

    private FacCoverResponse toResponse(RiFacCover f) {
        return new FacCoverResponse(
                f.getId(), f.getFacReference(),
                f.getPolicyId(), f.getPolicyNumber(),
                f.getReinsuranceCompanyId(), f.getReinsuranceCompanyName(),
                f.getStatus(),
                f.getSumInsuredCeded(), f.getPremiumRate(), f.getPremiumCeded(),
                f.getCommissionRate(), f.getCommissionAmount(), f.getNetPremium(),
                f.getCurrencyCode(), f.getCoverFrom(), f.getCoverTo(),
                f.getOfferSlipReference(), f.getTerms(),
                f.getApprovedBy(), f.getApprovedAt(),
                f.getCancelledBy(), f.getCancelledAt(), f.getCancellationReason(),
                f.getCreatedAt()
        );
    }
}
