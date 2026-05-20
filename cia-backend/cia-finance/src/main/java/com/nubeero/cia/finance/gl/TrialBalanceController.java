package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.dto.TrialBalanceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Trial balance REST endpoint.
 *
 * <p>{@code GET /api/v1/finance/trial-balance?asOf=YYYY-MM-DD} —
 * D4=A interprets {@code asOf} as business date (economic date) and
 * D3=A returns a flat per-account list with a footer summary.
 *
 * <p>RBAC: {@code FINANCE_VIEW}. The trial balance leaks no PII so a
 * single read-permission gates it.
 */
@RestController
@RequestMapping("/api/v1/finance/trial-balance")
@Tag(name = "Trial Balance",
     description = "Cumulative-since-inception trial balance at a given business date. Used by NAICOM's PrudentialReturnEngine (N03) and BalanceSheetEngine (N02), and by the Slice 1.9 reconciliation gate as the source-of-truth balance sheet aggregator.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class TrialBalanceController {

    private final TrialBalanceService service;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "Get the trial balance as of a business date",
               description = "Returns a flat per-account list with a footer summary. The asOf parameter is interpreted as a business date (economic date) per the Slice 1.4 D4 decision. Includes Income + Expense accounts (not just balance-sheet accounts) so cumulative P&L can be computed.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Trial balance as-of asOf",
            content = @Content(schema = @Schema(implementation = TrialBalanceResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "asOf missing or malformed", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content)
    })
    public ApiResponse<TrialBalanceResponse> get(
        @Parameter(description = "Business date (YYYY-MM-DD). Treated as inclusive — JEs with business_date <= asOf are aggregated.")
        @RequestParam("asOf") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.success(service.trialBalanceAsOf(asOf));
    }
}
