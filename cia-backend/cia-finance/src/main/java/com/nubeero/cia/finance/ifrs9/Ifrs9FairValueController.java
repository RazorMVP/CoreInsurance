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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST surface for the IFRS 9 fair-value remeasurement engine (Slice 3.4).
 *
 * <p>{@code POST /api/v1/finance/ifrs9/fair-value/recognise} accepts an
 * admin-supplied list of {@code (holdingId, fairValue)} pairs for one
 * period and posts the appropriate JEs (P&amp;L for FVPL, OCI reserve for
 * FVOCI debt/equity). Idempotent — re-runs that find
 * {@code closing_fair_value} already set on a holding's carrying-value
 * row skip silently per IFRS 9 §5.7's "fair value once" semantic.
 *
 * <p>RBAC: {@code FINANCE_APPROVE}.
 */
@RestController
@RequestMapping("/api/v1/finance/ifrs9/fair-value")
@Tag(name = "IFRS 9 — Fair Value (§5.7)",
     description = "Slice 3.4 fair-value remeasurement with classification-driven routing. FVPL → P&L (Dr 4250 / Cr 5330); FVOCI_DEBT → OCI reserve (Dr/Cr 3410); FVOCI_EQUITY → OCI reserve (Dr/Cr 3420). AC holdings refuse remeasurement. Idempotent via closing_fair_value IS NULL sentinel.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class Ifrs9FairValueController {

    private final FairValueEngine engine;

    @PostMapping("/recognise")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(summary = "Recognise fair-value remeasurement",
               description = "Accepts an admin-supplied list of (holdingId, fairValue) pairs for one period and posts the appropriate JEs by classification. Idempotent — re-runs that find closing_fair_value already set on a holding's carrying-value row skip silently.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Fair-value recognised",
            content = @Content(schema = @Schema(implementation = FairValueResult.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Period or holding not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "UnsupportedFairValueOperationException — AC holding cannot be remeasured to fair value", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Period is closed", content = @Content)
    })
    public ApiResponse<FairValueResult> recognise(@Valid @RequestBody RecogniseFairValuesRequest request) {
        // Preserve request order in the engine's iteration via LinkedHashMap
        // — keeps disclosure entries reproducible regardless of HashMap
        // hash-order drift across JVM versions.
        Map<UUID, BigDecimal> valuations = new LinkedHashMap<>();
        for (RecogniseFairValuesRequest.HoldingValuation v : request.valuations()) {
            valuations.put(v.holdingId(), v.fairValue());
        }
        return ApiResponse.success(engine.recognise(request.periodId(), valuations));
    }
}
