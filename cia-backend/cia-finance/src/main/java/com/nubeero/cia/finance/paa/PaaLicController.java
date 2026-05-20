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
 * REST surface for the LIC recognition engine (Slice 2.4).
 *
 * <p>{@code POST /api/v1/finance/paa/lic/recognise} runs the LIC engine
 * across every IFRS 17 group for the requested fiscal period. Idempotent
 * at the (group, period) grain — fails with 409 if any group has already
 * been recognised for the period (see
 * {@link LicRecognitionAlreadyDoneException}).
 *
 * <p>RBAC: {@code FINANCE_APPROVE} — same bar as period close, PPA, and LRC
 * recognition. The engine writes paa_lic disclosure rows but posts no JE
 * in v1 (see {@link LicEngine} class javadoc).
 */
@RestController
@RequestMapping("/api/v1/finance/paa/lic")
@Tag(name = "PAA — Liability for Incurred Claims (LIC)",
     description = "IFRS 17 PAA Slice 2.4 — claim roll-forward via SQL conditional-sum query. Posts paa_lic disclosure rows; v1 posts no JE (underlying GL already correct via SubledgerPostingService).")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class PaaLicController {

    private final LicEngine engine;

    @PostMapping("/recognise")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(summary = "Recognise LIC for a fiscal period",
               description = "Runs the LIC engine across every IFRS 17 group for the period. Idempotent at (group, period) — fails with 409 LicRecognitionAlreadyDoneException if any group has already been recognised. Disclosure-only in v1 (no JE).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "LIC recognised",
            content = @Content(schema = @Schema(implementation = LicRecognitionResult.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "periodId missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Period not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "LicRecognitionAlreadyDoneException", content = @Content)
    })
    public ApiResponse<LicRecognitionResult> recognise(@Valid @RequestBody RecogniseLicRequest request) {
        return ApiResponse.success(engine.recognise(request.periodId()));
    }

    public record RecogniseLicRequest(@NotNull UUID periodId) {}
}
