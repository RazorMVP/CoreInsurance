package com.nubeero.cia.claims;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.claims.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Claim Expenses",
     description = "Per-claim expenses (surveyor fees, repair costs, legal fees, etc). State machine: PENDING → APPROVED → (cascade to a CreditNote via CLAIM_EXPENSE_APPROVED event) or CANCELLED. The approval is what triggers GL impact via SubledgerPostingService.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class ClaimExpenseController {

    private final ClaimExpenseService service;

    @GetMapping
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    @Operation(summary = "List expenses on a claim (paginated)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Expense page",
            content = @Content(schema = @Schema(implementation = ClaimExpenseResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content)
    })
    public ApiResponse<Page<ClaimExpenseResponse>> list(
            @PathVariable UUID claimId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(
                service.findByClaimId(claimId, pageable).map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    @Operation(summary = "Get expense detail")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Expense found",
            content = @Content(schema = @Schema(implementation = ClaimExpenseResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Expense not found", content = @Content)
    })
    public ApiResponse<ClaimExpenseResponse> get(
            @PathVariable UUID claimId,
            @PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CLAIMS_CREATE')")
    @Operation(summary = "Add a new expense (PENDING)",
               description = "Vendor info is mandatory. Status starts at PENDING; approval is a separate step.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Expense added",
            content = @Content(schema = @Schema(implementation = ClaimExpenseResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_CREATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content)
    })
    public ApiResponse<ClaimExpenseResponse> add(
            @PathVariable UUID claimId,
            @Valid @RequestBody AddExpenseRequest req) {
        return ApiResponse.success(toResponse(service.add(claimId, req)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    @Operation(summary = "Approve a PENDING expense",
               description = "Flips status to APPROVED and fires CLAIM_EXPENSE_APPROVED event → SubledgerPostingService cascades a CreditNote (payable) + posts the JE. Triggers period-lock check via the interceptor.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Expense approved",
            content = @Content(schema = @Schema(implementation = ClaimExpenseResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Expense not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Already approved or cancelled", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Target period is closed", content = @Content)
    })
    public ApiResponse<ClaimExpenseResponse> approve(
            @PathVariable UUID claimId,
            @PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.approve(id)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    @Operation(summary = "Cancel an expense",
               description = "Allowed only on PENDING expenses (no GL impact yet). For approved expenses that need to be undone, reverse the cascaded payment first.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Expense cancelled",
            content = @Content(schema = @Schema(implementation = ClaimExpenseResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Expense not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Cannot cancel APPROVED expense — reverse the payment first", content = @Content)
    })
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
