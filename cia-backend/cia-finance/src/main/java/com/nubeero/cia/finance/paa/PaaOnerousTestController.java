package com.nubeero.cia.finance.paa;

import com.nubeero.cia.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST surface for the IFRS 17 §47-49 onerous contract test (Slice 2.7).
 *
 * <p>{@code POST /api/v1/finance/paa/onerous-test/run} runs the onerous
 * test for every paa_lrc row in the requested period, posting loss-
 * component increase or reversal JEs as needed. Naturally idempotent —
 * a re-run with no underlying movement produces a no-op result.
 *
 * <p>RBAC: {@code FINANCE_APPROVE} — same bar as other PAA period-close
 * engines (LRC, LIC, DiscountUnwind).
 */
@RestController
@RequestMapping("/api/v1/finance/paa/onerous-test")
@Tag(name = "PAA — Onerous Contract Test (§47-49)",
     description = "IFRS 17 PAA Slice 2.7 — cumulative-state target reconciliation. Posts loss-component increase (Dr 5150 / Cr 2130) or reversal JEs as needed. Naturally idempotent — re-runs with no underlying movement produce a no-op result.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class PaaOnerousTestController {

    private final OnerousContractTestEngine engine;

    @PostMapping("/run")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(summary = "Run the onerous contract test for a period",
               description = "Tests every paa_lrc row in the period; posts JE for net loss-component movement per group. Idempotent (delta-based — no movement → no-op).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Onerous test complete",
            content = @Content(schema = @Schema(implementation = OnerousTestResult.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "periodId missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Period not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Period is closed", content = @Content)
    })
    public ApiResponse<OnerousTestResult> run(@Valid @RequestBody OnerousTestRequest request) {
        return ApiResponse.success(engine.test(request.periodId()));
    }

    public record OnerousTestRequest(@NotNull UUID periodId) {}
}
