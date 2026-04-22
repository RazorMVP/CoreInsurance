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
@RequestMapping("/api/v1/ri/allocations")
@RequiredArgsConstructor
public class RiAllocationController {

    private final AllocationService service;

    @GetMapping
    @PreAuthorize("hasRole('REINSURANCE_VIEW')")
    public ApiResponse<Page<AllocationResponse>> list(
            @RequestParam(required = false) UUID policyId,
            @RequestParam(required = false) AllocationStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.list(policyId, status, pageable).map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('REINSURANCE_VIEW')")
    public ApiResponse<AllocationResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('REINSURANCE_CREATE')")
    public ApiResponse<AllocationResponse> allocate(@Valid @RequestBody AllocateRequest req) {
        return ApiResponse.success(toResponse(service.allocate(
                req.policyId(), req.policyNumber(), req.treatyId(),
                req.sumInsured(), req.premium(), req.currencyCode(), req.endorsementId())));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    public ApiResponse<AllocationResponse> confirm(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.confirm(id)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    public ApiResponse<AllocationResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.cancel(id)));
    }

    // ─── Mapping ──────────────────────────────────────────────────────────

    private AllocationResponse toResponse(RiAllocation a) {
        return new AllocationResponse(
                a.getId(), a.getAllocationNumber(),
                a.getPolicyId(), a.getPolicyNumber(), a.getEndorsementId(),
                a.getTreaty() != null ? a.getTreaty().getId() : null,
                a.getTreatyType(), a.getStatus(),
                a.getOurShareSumInsured(), a.getRetainedAmount(),
                a.getCededAmount(), a.getExcessAmount(),
                a.getOurSharePremium(), a.getRetainedPremium(), a.getCededPremium(),
                a.getCurrencyCode(),
                a.getLines().stream().map(l -> new AllocationLineResponse(
                        l.getId(), l.getReinsuranceCompanyId(), l.getReinsuranceCompanyName(),
                        l.getSharePercentage(), l.getCededAmount(), l.getCededPremium(),
                        l.getCommissionRate(), l.getCommissionAmount()
                )).toList(),
                a.getCreatedAt()
        );
    }
}
