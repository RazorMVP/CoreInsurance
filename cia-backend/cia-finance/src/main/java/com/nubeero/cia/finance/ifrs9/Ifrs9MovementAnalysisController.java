package com.nubeero.cia.finance.ifrs9;

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
 * REST surface for the IFRS 9 §B5.5.39 movement analysis (Slice 3.7).
 *
 * <p>{@code GET /api/v1/finance/ifrs9/movement-analysis/{periodId}}
 * returns the full §B5.5.39 disclosure for the period: per-holding
 * investment roll-forward + premium-receivable ECL aggregate.
 *
 * <p>RBAC: {@code FINANCE_VIEW} — read-only disclosure access, mirrors
 * Slice 2.8's narrower bar for disclosure GET endpoints.
 */
@RestController
@RequestMapping("/api/v1/finance/ifrs9/movement-analysis")
@Tag(name = "IFRS 9 — §B5.5.39 Movement Analysis",
     description = "Slice 3.7 disclosure relay. Composes investment roll-forward from V40 ifrs9_investment_movement_analysis SQL view + premium-receivable ECL aggregate from JE narrative on account 1340. Read-only; consumed by Phase 4 Ifrs9DisclosureEngine.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class Ifrs9MovementAnalysisController {

    private final Ifrs9MovementAnalysisService service;

    @GetMapping("/{periodId}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "Get §B5.5.39 movement analysis for a period",
               description = "Returns two sections: investments (from V40 view, aggregated by holding + classification totals) and premium-receivable ECL (derived from JE aggregate on account 1340 by business_date — opening = sum prior periods, closing = sum through period-end, movement = closing − opening).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Movement analysis",
            content = @Content(schema = @Schema(implementation = Ifrs9MovementAnalysis.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Period not found", content = @Content)
    })
    public ApiResponse<Ifrs9MovementAnalysis> get(@PathVariable UUID periodId) {
        return ApiResponse.success(service.compute(periodId));
    }
}
