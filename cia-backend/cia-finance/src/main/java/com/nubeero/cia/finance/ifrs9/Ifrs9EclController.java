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

import java.util.List;

/**
 * REST surface for the IFRS 9 Expected Credit Loss engine (Slice 3.5).
 *
 * <p>{@code POST /api/v1/finance/ifrs9/ecl/recognise} accepts an admin-supplied
 * batch of target ECL amounts (and optional stage transitions) for one
 * period. Posts JEs for the delta vs cumulative prior ECL.
 *
 * <p>RBAC: {@code FINANCE_APPROVE}.
 */
@RestController
@RequestMapping("/api/v1/finance/ifrs9/ecl")
@Tag(name = "IFRS 9 — Investment ECL (§5.5)",
     description = "Slice 3.5 Expected Credit Loss with three-stage routing. AC holdings: ECL reduces asset directly (Dr 5310 ECL_EXPENSE_AC / Cr 1140 ECL_AC_ALLOWANCE). FVOCI_DEBT: ECL routes to OCI reserve (Dr 5310 / Cr 3410) while carrying value stays at fair value — §5.7.10A rule. FVPL: no ECL.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class Ifrs9EclController {

    private final InvestmentEclEngine engine;

    @PostMapping("/recognise")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(summary = "Recognise investment ECL",
               description = "Accepts an admin-supplied batch of target ECL amounts + optional stage transitions for one period. Posts JEs for the delta vs cumulative prior ECL. Routing depends on holding classification (AC vs FVOCI_DEBT).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ECL recognised",
            content = @Content(schema = @Schema(implementation = EclRecognitionResult.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Period or holding not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Unsupported ECL on FVPL holding (FVPL has no ECL — impairment IS the fair-value movement)", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Period is closed", content = @Content)
    })
    public ApiResponse<EclRecognitionResult> recognise(@Valid @RequestBody RecogniseEclRequest request) {
        List<InvestmentEclEngine.EclInput> inputs = request.ecls().stream()
            .map(e -> new InvestmentEclEngine.EclInput(e.holdingId(), e.eclAmount(), e.eclStage()))
            .toList();
        return ApiResponse.success(engine.recognise(request.periodId(), inputs));
    }
}
