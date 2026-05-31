package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.reinsurance.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ri/allocations")
@Tag(name = "Reinsurance Allocations",
     description = "Per-policy treaty allocation. Surplus: retain ≤ Retention Limit, cede to surplus up to Surplus Limit, excess tagged if beyond gross capacity. QS: split by fixed share %. XOL: retain first layer, cede above retention. State machine: AUTO_ALLOCATED → CONFIRMED → APPROVED, or CANCELLED. Allocation lines record per-participant ceded amount + commission.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class RiAllocationController {

    private final AllocationService service;

    @GetMapping
    @PreAuthorize("hasRole('REINSURANCE_VIEW')")
    @Operation(summary = "List allocations (paginated, filterable)",
               description = "Filter by policyId and/or status. Each allocation row embeds the per-participant lines.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Allocation page",
            content = @Content(schema = @Schema(implementation = AllocationResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_VIEW", content = @Content)
    })
    public ApiResponse<List<AllocationResponse>> list(
            @RequestParam(required = false) UUID policyId,
            @RequestParam(required = false) AllocationStatus status,
            @PageableDefault(size = 2000) Pageable pageable) {
        var page = service.list(policyId, status, pageable);
        return ApiResponse.success(page.map(this::toResponse).getContent(),
                ApiMeta.builder()
                        .total(page.getTotalElements())
                        .page(page.getNumber())
                        .size(page.getSize())
                        .build());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('REINSURANCE_VIEW')")
    @Operation(summary = "Get allocation detail (with participant lines)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Allocation found",
            content = @Content(schema = @Schema(implementation = AllocationResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Allocation not found", content = @Content)
    })
    public ApiResponse<AllocationResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('REINSURANCE_CREATE')")
    @Operation(summary = "Allocate a policy against a treaty",
               description = "Runs the treaty-type-specific allocation: Surplus / QS / XOL. Computes retained vs ceded amounts, per-participant lines, and commission. Status starts at AUTO_ALLOCATED for treaty-bound allocations. Endorsements that change SI trigger proportional reallocation.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Allocation created",
            content = @Content(schema = @Schema(implementation = AllocationResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error — policy not eligible, treaty inactive, or SI exceeds gross capacity", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_CREATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy or treaty not found", content = @Content)
    })
    public ApiResponse<AllocationResponse> allocate(@Valid @RequestBody AllocateRequest req) {
        return ApiResponse.success(toResponse(service.allocate(
                req.policyId(), req.policyNumber(), req.treatyId(),
                req.sumInsured(), req.premium(), req.currencyCode(), req.endorsementId())));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    @Operation(summary = "Confirm an AUTO_ALLOCATED allocation",
               description = "Required for allocations on policies exceeding gross capacity. Transitions AUTO_ALLOCATED → CONFIRMED. Reinsurance officer reviews the allocation lines before finance impact.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Allocation confirmed",
            content = @Content(schema = @Schema(implementation = AllocationResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Allocation not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Allocation not in AUTO_ALLOCATED state", content = @Content)
    })
    public ApiResponse<AllocationResponse> confirm(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.confirm(id)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    @Operation(summary = "Cancel an allocation",
               description = "Used when the underlying policy is cancelled or the allocation needs to be re-run (e.g. batch reallocation under a new treaty). Reverses any cascaded credit notes from this allocation.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Allocation cancelled",
            content = @Content(schema = @Schema(implementation = AllocationResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks REINSURANCE_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Allocation not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Allocation already in terminal state", content = @Content)
    })
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
