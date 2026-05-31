package com.nubeero.cia.endorsement;

import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.endorsement.dto.*;
import com.nubeero.cia.policy.Policy;
import com.nubeero.cia.policy.PolicyRepository;
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

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/endorsements")
@Tag(name = "Endorsements (Module 4)",
     description = "Mid-term policy changes — Renewal, Extension, Cancellation, Reversal, Reduction in Period, Change in Period, Increase/Decrease SI, Add/Delete Items. Pro-rata premium adjustment: (Annual Premium / 365) × Days. Approval fires ENDORSEMENT_APPROVED event → SubledgerPostingService cascades a DebitNote (additional premium) or CreditNote (return premium) + JE.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class EndorsementController {

    private final EndorsementService service;
    private final PolicyRepository policyRepository;

    @GetMapping
    @PreAuthorize("hasRole('UNDERWRITING_VIEW')")
    @Operation(summary = "List endorsements (paginated, filterable)",
               description = "Filter by policyId, status, and/or customerId. Each endorsement embeds its risk schedule. Used by the Debit Note Analysis Report (Module 4 feature).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Endorsement page",
            content = @Content(schema = @Schema(implementation = EndorsementResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_VIEW", content = @Content)
    })
    public ApiResponse<List<EndorsementResponse>> list(
            @RequestParam(required = false) UUID policyId,
            @RequestParam(required = false) EndorsementStatus status,
            @RequestParam(required = false) UUID customerId,
            @PageableDefault(size = 2000) Pageable pageable) {
        var page = service.list(policyId, status, customerId, pageable);
        return ApiResponse.success(page.map(this::toResponse).getContent(),
                ApiMeta.builder()
                        .total(page.getTotalElements())
                        .page(page.getNumber())
                        .size(page.getSize())
                        .build());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('UNDERWRITING_VIEW')")
    @Operation(summary = "Get endorsement detail")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Endorsement found",
            content = @Content(schema = @Schema(implementation = EndorsementResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Endorsement not found", content = @Content)
    })
    public ApiResponse<EndorsementResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('UNDERWRITING_CREATE')")
    @Operation(summary = "Create an endorsement (DRAFT)",
               description = "Creates the endorsement in DRAFT. Type-specific validation: period changes require new dates, SI changes require newSumInsured, add/delete items require itemDescription. Pro-rata premium adjustment is computed by service.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Endorsement created",
            content = @Content(schema = @Schema(implementation = EndorsementResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error or type-specific fields missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_CREATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Policy not ACTIVE — cannot endorse", content = @Content)
    })
    public ApiResponse<EndorsementResponse> create(
            @Valid @RequestBody CreateEndorsementRequest req) {
        return ApiResponse.success(toResponse(service.create(req)));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('UNDERWRITING_CREATE')")
    @Operation(summary = "Submit endorsement for approval",
               description = "Transitions DRAFT → PENDING_APPROVAL. Starts the EndorsementApprovalWorkflow Temporal workflow.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Submitted for approval",
            content = @Content(schema = @Schema(implementation = EndorsementResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_CREATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Endorsement not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Endorsement not in DRAFT state", content = @Content)
    })
    public ApiResponse<EndorsementResponse> submit(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.submitForApproval(id)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('UNDERWRITING_APPROVE')")
    @Operation(summary = "Approve an endorsement",
               description = "Transitions PENDING_APPROVAL → APPROVED. Fires ENDORSEMENT_APPROVED event → SubledgerPostingService cascades a DebitNote (positive adjustment) or CreditNote (negative adjustment) + JE. For Increase SI or Add Items types, RI allocation re-runs proportionally. Period-lock check applies.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Endorsement approved",
            content = @Content(schema = @Schema(implementation = EndorsementResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_APPROVE or amount exceeds approver tier", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Endorsement not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Endorsement not in PENDING_APPROVAL state", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Target period is closed", content = @Content)
    })
    public ApiResponse<EndorsementResponse> approve(
            @PathVariable UUID id,
            @RequestBody(required = false) ApproveEndorsementRequest req) {
        String notes = req != null ? req.notes() : null;
        return ApiResponse.success(toResponse(service.approve(id, notes)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('UNDERWRITING_APPROVE')")
    @Operation(summary = "Reject an endorsement",
               description = "Transitions PENDING_APPROVAL → DRAFT with rejection reason — underwriter can edit and re-submit. No GL impact.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Endorsement rejected",
            content = @Content(schema = @Schema(implementation = EndorsementResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Endorsement not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Endorsement not in PENDING_APPROVAL state", content = @Content)
    })
    public ApiResponse<EndorsementResponse> reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectEndorsementRequest req) {
        return ApiResponse.success(toResponse(service.reject(id, req.reason())));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    @Operation(summary = "Cancel an endorsement",
               description = "For DRAFT endorsements: hard-cancel (no GL impact). For APPROVED endorsements: reverses the cascaded debit/credit note + JE (reversal carve-out applies — can cross closed periods).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Endorsement cancelled",
            content = @Content(schema = @Schema(implementation = EndorsementResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Endorsement not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Endorsement already in terminal state", content = @Content)
    })
    public ApiResponse<EndorsementResponse> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelEndorsementRequest req) {
        return ApiResponse.success(toResponse(service.cancel(id, req.reason())));
    }

    @GetMapping("/premium-preview")
    @PreAuthorize("hasRole('UNDERWRITING_VIEW')")
    @Operation(summary = "Preview pro-rata premium adjustment",
               description = "Computes what the premium adjustment WOULD be for a given policy + effective date + new net premium, without creating the endorsement. Returns the type classification (ADDITIONAL_PREMIUM / RETURN_PREMIUM / NON_PREMIUM_BEARING) based on sign of the adjustment. Used by the CreateEndorsementSheet live-preview.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Preview",
            content = @Content(schema = @Schema(implementation = PremiumPreviewResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error (date malformed, etc.)", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content)
    })
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
