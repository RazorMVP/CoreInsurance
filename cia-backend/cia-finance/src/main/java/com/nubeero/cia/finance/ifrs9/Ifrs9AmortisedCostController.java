package com.nubeero.cia.finance.ifrs9;

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
 * REST surface for the IFRS 9 effective-interest-method engine (Slice 3.3).
 *
 * <p>{@code POST /api/v1/finance/ifrs9/amortised-cost/recognise} runs the
 * engine across every eligible AC + FVOCI_DEBT holding for the requested
 * fiscal period. Idempotent at the (holding, period) grain — fails with
 * 409 if any holding has already been recognised for the period (see
 * {@link AmortisedCostAlreadyDoneException}).
 *
 * <p>RBAC: {@code FINANCE_APPROVE} — same bar as the PAA engines.
 */
@RestController
@RequestMapping("/api/v1/finance/ifrs9/amortised-cost")
@Tag(name = "IFRS 9 — Amortised Cost (§5.4.1)",
     description = "Slice 3.3 effective interest method. Posts Dr 1250 INVESTMENT_AT_AMORTISED_COST / Cr 4210 INTEREST_INCOME_AC for accruals + additional Dr 1230 / Cr 1250 net-down lines on coupon receipts. Idempotency triple: (IFRS9_AMORTISED_COST, INTEREST_ACCRUAL, holdingId+periodId).")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class Ifrs9AmortisedCostController {

    private final AmortisedCostEngine engine;

    @PostMapping("/recognise")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(summary = "Recognise interest accrual for a period",
               description = "Runs the engine across every AC + FVOCI_DEBT holding for the period. Idempotent at (holding, period) — fails with 409 AmortisedCostAlreadyDoneException if any holding has already been recognised.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Interest accrual recognised",
            content = @Content(schema = @Schema(implementation = AmortisedCostResult.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "periodId missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Period not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "AmortisedCostAlreadyDoneException", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Period is closed", content = @Content)
    })
    public ApiResponse<AmortisedCostResult> recognise(@Valid @RequestBody RecogniseAmortisedCostRequest request) {
        return ApiResponse.success(engine.recognise(request.periodId()));
    }

    public record RecogniseAmortisedCostRequest(@NotNull UUID periodId) {}
}
