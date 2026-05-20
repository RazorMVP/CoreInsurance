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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST surface for PAA period close (Slice 2.5).
 *
 * <ul>
 *   <li>{@code POST /api/v1/finance/paa/period-close/{periodId}} — runs LRC
 *       + LIC engines for the period and returns the §83 / §84
 *       Insurance Service Result alongside the engine outputs. Idempotent:
 *       engines that have already run for the period are skipped.</li>
 *   <li>{@code GET  /api/v1/finance/paa/insurance-service-result/{periodId}}
 *       — recomputes the §83 / §84 disclosure view from the paa_lrc +
 *       paa_lic state (no engine invocation). Read-only.</li>
 * </ul>
 *
 * <p>RBAC: {@code FINANCE_APPROVE} for close (same bar as PPA, period
 * close, LRC/LIC recognition); {@code FINANCE_VIEW} for the read-only
 * service-result GET.
 */
@RestController
@RequestMapping("/api/v1/finance/paa")
@Tag(name = "PAA — Period Close Orchestrator",
     description = "IFRS 17 PAA Slice 2.5 orchestrator. Runs LRC + LIC engines for a period and returns the IFRS 17 §83/§84 Insurance Service Result alongside the engine outputs. Companion read endpoint recomputes §83/§84 from the current paa_lrc + paa_lic state without invoking engines.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class PaaPeriodCloseController {

    private final PaaPeriodCloseService periodCloseService;
    private final InsuranceServiceResultService insuranceServiceResultService;

    @PostMapping("/period-close/{periodId}")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(summary = "Run PAA period close (LRC + LIC + §83/§84)",
               description = "Idempotent orchestrator: invokes LrcEngine + LicEngine in sequence (skipping any that already ran for the period), then computes the §83/§84 Insurance Service Result. Use the more granular engine endpoints if you need to re-run a single stage.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Period close complete",
            content = @Content(schema = @Schema(implementation = PaaPeriodCloseResult.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Period not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Period is closed", content = @Content)
    })
    public ApiResponse<PaaPeriodCloseResult> closePeriod(@PathVariable UUID periodId) {
        return ApiResponse.success(periodCloseService.closePeriod(periodId));
    }

    @GetMapping("/insurance-service-result/{periodId}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "Get §83/§84 Insurance Service Result",
               description = "Read-only — recomputes the §83 (insurance revenue) / §84 (insurance service expense) breakdown from the current paa_lrc + paa_lic state. No engine invocation.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "§83/§84 result",
            content = @Content(schema = @Schema(implementation = InsuranceServiceResult.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Period not found", content = @Content)
    })
    public ApiResponse<InsuranceServiceResult> insuranceServiceResult(@PathVariable UUID periodId) {
        return ApiResponse.success(insuranceServiceResultService.compute(periodId));
    }
}
