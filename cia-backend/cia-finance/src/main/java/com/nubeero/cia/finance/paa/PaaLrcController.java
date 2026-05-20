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
 * REST surface for the LRC recognition engine (Slice 2.3).
 *
 * <p>{@code POST /api/v1/finance/paa/lrc/recognise} runs the LRC engine
 * across every IFRS 17 group for the requested fiscal period. Idempotent
 * at the (group, period) grain — a re-run after partial completion picks
 * up only groups that haven't already been recognised, and the request
 * fails with 409 if any group has already been recognised for the period
 * (see {@link LrcRecognitionAlreadyDoneException}).
 *
 * <p>RBAC: {@code FINANCE_APPROVE} — same bar as period close + PPA. The
 * engine writes paa_lrc rows and posts journal entries via the
 * {@code JournalEntryService} gateway; both are non-reversible from this
 * endpoint and require the same level of authority.
 */
@RestController
@RequestMapping("/api/v1/finance/paa/lrc")
@Tag(name = "PAA — Liability for Remaining Coverage (LRC)",
     description = "IFRS 17 PAA Slice 2.3 — straight-line daily premium recognition (Dr 2110 / Cr 4110 via JE gateway). Posts paa_lrc rows + JEs.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class PaaLrcController {

    private final LrcEngine engine;

    @PostMapping("/recognise")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(summary = "Recognise LRC for a fiscal period",
               description = "Runs the LRC engine across every IFRS 17 group for the period. Idempotent at (group, period) — fails with 409 LrcRecognitionAlreadyDoneException if any group has already been recognised. Posts JEs via the Slice 1.4 gateway; period-lock interceptor applies (423 if locked).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "LRC recognised for all groups in period",
            content = @Content(schema = @Schema(implementation = LrcRecognitionResult.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "periodId missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Period not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "LrcRecognitionAlreadyDoneException — at least one group already recognised", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Period is closed", content = @Content)
    })
    public ApiResponse<LrcRecognitionResult> recognise(@Valid @RequestBody RecogniseLrcRequest request) {
        return ApiResponse.success(engine.recognise(request.periodId()));
    }

    public record RecogniseLrcRequest(@NotNull UUID periodId) {}
}
