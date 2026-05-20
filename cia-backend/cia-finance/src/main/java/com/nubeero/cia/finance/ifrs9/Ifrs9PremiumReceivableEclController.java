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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the IFRS 9 §5.5.15 premium-receivable ECL engine
 * (Slice 3.6).
 *
 * <p>{@code POST /api/v1/finance/ifrs9/premium-receivable-ecl/recognise}
 * accepts an admin-supplied provision matrix (aging buckets × default
 * rates), computes the lifetime ECL, and posts the JE for the movement
 * versus cumulative prior ECL.
 *
 * <p>RBAC: {@code FINANCE_APPROVE}.
 */
@RestController
@RequestMapping("/api/v1/finance/ifrs9/premium-receivable-ecl")
@Tag(name = "IFRS 9 — Premium Receivable ECL (§5.5.15)",
     description = "Slice 3.6 simplified-approach engine. Admin supplies aging-bucket provision matrix [(label, outstandingAmount, defaultRate)]; engine computes lifetime ECL = Σ(outstanding × rate) and posts the delta vs cumulative prior allowance. Posts Dr 5350 PREMIUM_ECL_EXPENSE / Cr 1340 PREMIUM_ECL_ALLOWANCE (increase) or reverse (release). Provision matrix is embedded in JE narrative as §B5.5.36 disclosure substrate.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class Ifrs9PremiumReceivableEclController {

    private final PremiumReceivableEclEngine engine;

    @PostMapping("/recognise")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(summary = "Recognise premium-receivable ECL",
               description = "Accepts an admin-supplied provision matrix for one period, computes lifetime ECL = Σ(outstanding × default_rate), and posts the JE for the movement versus cumulative prior ECL. Idempotent at (period, ECL_PROVISION_MATRIX) — re-runs with the same matrix produce a no-op delta.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Premium-receivable ECL recognised",
            content = @Content(schema = @Schema(implementation = PremiumReceivableEclResult.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error (matrix shape, missing fields)", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Period not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Period is closed", content = @Content)
    })
    public ApiResponse<PremiumReceivableEclResult> recognise(
            @Valid @RequestBody RecognisePremiumReceivableEclRequest request) {
        return ApiResponse.success(engine.recognise(request.periodId(), request.agingBuckets()));
    }
}
