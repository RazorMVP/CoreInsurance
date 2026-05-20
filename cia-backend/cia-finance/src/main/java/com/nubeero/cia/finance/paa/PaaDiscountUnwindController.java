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
 * REST surface for the IFRS 17 §87-92 discount unwind engine (Slice 2.6).
 *
 * <p>{@code POST /api/v1/finance/paa/finance-unwind/recognise} computes
 * discount unwind for every paa_lic row in the requested period, posts
 * the JE (P&amp;L or OCI per tenant election), and updates the paa_lic row.
 * No-op for tenants where {@code paa_config.discount_lic = FALSE}.
 *
 * <p>RBAC: {@code FINANCE_APPROVE} — same bar as the other PAA period-close
 * operations.
 */
@RestController
@RequestMapping("/api/v1/finance/paa/finance-unwind")
@Tag(name = "PAA — Discount Unwind (§87-92)",
     description = "IFRS 17 PAA Slice 2.6 — discount unwind on paa_lic rows. P&L vs OCI routing per paa_config.oci_election (§88(b)). Posts Dr 5520 / Cr 2140 (P&L) or Dr 3430 / Cr 2140 (OCI). No-op for tenants where paa_config.discount_lic = FALSE.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class PaaDiscountUnwindController {

    private final DiscountUnwindEngine engine;

    @PostMapping("/recognise")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(summary = "Recognise discount unwind for a period",
               description = "Computes unwind for every paa_lic row in the period, posts JE, updates paa_lic.discount_unwind_to_date. Idempotent via the JE-gateway triple. Routes to P&L or OCI per tenant election in paa_config.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Discount unwind recognised",
            content = @Content(schema = @Schema(implementation = DiscountUnwindResult.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "periodId missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Period not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Period is closed", content = @Content)
    })
    public ApiResponse<DiscountUnwindResult> recognise(@Valid @RequestBody RecogniseUnwindRequest request) {
        return ApiResponse.success(engine.recognise(request.periodId()));
    }

    public record RecogniseUnwindRequest(@NotNull UUID periodId) {}
}
