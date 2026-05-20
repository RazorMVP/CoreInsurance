package com.nubeero.cia.finance.paa;

import com.nubeero.cia.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST surface for the IFRS 17 §103 movement analysis (Slice 2.8).
 *
 * <p>{@code GET /api/v1/finance/paa/movement-analysis/{periodId}} returns
 * the full §103-shaped roll-forward for the period — LRC totals, LIC
 * totals, per-(portfolio × cohort × onerousness) breakdown.
 *
 * <p>Read-only. RBAC: {@code FINANCE_VIEW} (same narrower bar as the
 * Insurance Service Result read endpoint — disclosure data should be
 * widely consultable within finance/audit teams).
 */
@RestController
@RequestMapping("/api/v1/finance/paa/movement-analysis")
@Tag(name = "PAA — §103 Movement Analysis",
     description = "IFRS 17 §103 disclosure relay (Slice 2.8). Reads from V38 paa_movement_analysis SQL view — LRC totals, LIC totals, per-(portfolio × cohort × onerousness) breakdown. Read-only; consumed by Phase 4 Ifrs17DisclosureEngine.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class PaaMovementAnalysisController {

    private final MovementAnalysisService service;

    @GetMapping("/{periodId}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "Get §103 movement analysis for a period",
               description = "Returns the §103-shaped roll-forward: opening balances + period movements (premiums earned, claims incurred, finance income/expense, OCI elements) + closing balances. Computed from the V38 paa_movement_analysis view.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Movement analysis",
            content = @Content(schema = @Schema(implementation = MovementAnalysis.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Period not found", content = @Content)
    })
    public ApiResponse<MovementAnalysis> get(@PathVariable UUID periodId) {
        return ApiResponse.success(service.compute(periodId));
    }
}
