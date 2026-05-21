package com.nubeero.cia.finance.ifrs9;

import com.nubeero.cia.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST surface for IFRS 9 investment-holding lifecycle (Slice 3.2).
 *
 * <ul>
 *   <li>{@code POST   /api/v1/finance/ifrs9/holdings} — register new holding;
 *       service runs §4.1 classification automatically.</li>
 *   <li>{@code POST   /api/v1/finance/ifrs9/holdings/{id}/reclassify} —
 *       apply §B4.1.26 reclassification (rare, audited).</li>
 *   <li>{@code GET    /api/v1/finance/ifrs9/holdings} — list all active holdings.</li>
 *   <li>{@code GET    /api/v1/finance/ifrs9/holdings/{id}} — holding detail.</li>
 * </ul>
 *
 * <p>RBAC: {@code FINANCE_APPROVE} for register + reclassify (creates GL
 * impact downstream); {@code FINANCE_VIEW} for reads.
 */
@RestController
@RequestMapping("/api/v1/finance/ifrs9/holdings")
@Tag(name = "IFRS 9 — Investment Holdings",
     description = "Slice 3.2 — register investment holdings under §4.1 classification (SPPI test + business model → FVPL / FVOCI_DEBT / FVOCI_EQUITY / AMORTISED_COST). Reclassification follows §B4.1.26 (rare, audited via Type-2 SCD investment_classification_history).")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class Ifrs9HoldingController {

    private final InvestmentClassificationService classificationService;
    private final InvestmentHoldingRepository holdingRepository;
    private final InvestmentClassificationHistoryRepository historyRepository;

    @PostMapping
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(summary = "Register a new investment holding",
               description = "Runs §4.1 classification automatically based on business_model + SPPI flag in the request, then persists the holding. The chosen classification governs downstream JE routing (FVPL → P&L, FVOCI → OCI, AC → asset).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Holding registered",
            content = @Content(schema = @Schema(implementation = InvestmentHoldingResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content)
    })
    public ApiResponse<InvestmentHoldingResponse> register(@Valid @RequestBody RegisterHoldingRequest request) {
        return ApiResponse.success(
            InvestmentHoldingResponse.from(classificationService.register(request)));
    }

    @PostMapping("/{holdingId}/reclassify")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(summary = "Reclassify a holding (§B4.1.26)",
               description = "Apply a §B4.1.26 reclassification (business model change). Rare event — appends a row to investment_classification_history (Type-2 SCD); a CHECK constraint rejects no-op rows.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Reclassification applied",
            content = @Content(schema = @Schema(implementation = InvestmentHoldingResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error or attempted no-op reclassification", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Holding not found", content = @Content)
    })
    public ApiResponse<InvestmentHoldingResponse> reclassify(
            @PathVariable UUID holdingId,
            @Valid @RequestBody ReclassifyHoldingRequest request) {
        return ApiResponse.success(
            InvestmentHoldingResponse.from(classificationService.reclassify(holdingId, request)));
    }

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "List active investment holdings",
               description = "Returns every non-soft-deleted holding, ordered by security name.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Holdings list",
            content = @Content(schema = @Schema(implementation = InvestmentHoldingResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ApiResponse<List<InvestmentHoldingResponse>> list() {
        List<InvestmentHoldingResponse> list = holdingRepository
            .findByDeletedAtIsNullOrderBySecurityNameAsc()
            .stream()
            .map(InvestmentHoldingResponse::from)
            .toList();
        return ApiResponse.success(list);
    }

    @GetMapping("/{holdingId}/classification-history")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "Get classification history of a holding (§B4.1.26)",
               description = "Returns every Type-2 SCD row from investment_classification_history for the holding, ordered ASC by reclassification date. NAICOM auditors require this trail per §B4.1.26 — previous → new classification, the reclassification date, the textual reason, and the approver.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Classification history",
            content = @Content(schema = @Schema(implementation = InvestmentClassificationHistoryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ApiResponse<List<InvestmentClassificationHistoryResponse>> classificationHistory(@PathVariable UUID holdingId) {
        List<InvestmentClassificationHistoryResponse> list = historyRepository
            .findByHoldingIdAndDeletedAtIsNullOrderByReclassificationDateAsc(holdingId)
            .stream()
            .map(InvestmentClassificationHistoryResponse::from)
            .toList();
        return ApiResponse.success(list);
    }

    @GetMapping("/{holdingId}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "Get holding detail")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Holding found",
            content = @Content(schema = @Schema(implementation = InvestmentHoldingResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "InvestmentHoldingNotFoundException", content = @Content)
    })
    public ApiResponse<InvestmentHoldingResponse> get(@PathVariable UUID holdingId) {
        InvestmentHolding h = holdingRepository.findById(holdingId)
            .filter(x -> x.getDeletedAt() == null)
            .orElseThrow(() -> new InvestmentHoldingNotFoundException(holdingId));
        return ApiResponse.success(InvestmentHoldingResponse.from(h));
    }
}
