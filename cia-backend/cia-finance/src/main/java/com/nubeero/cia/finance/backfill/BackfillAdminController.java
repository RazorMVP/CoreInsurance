package com.nubeero.cia.finance.backfill;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.backfill.dto.BackfillStatusResponse;
import com.nubeero.cia.finance.backfill.dto.StartBackfillRequest;
import com.nubeero.cia.finance.backfill.dto.StartBackfillResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only endpoints that drive the retroactive JE backfill workflow
 * (Slice 1.8 — Module 12 Period-End Closures).
 *
 * <p>Both endpoints are gated by the {@code PLATFORM_ADMIN} role rather than
 * any of the finance roles: backfill is a one-time mechanism for moving from
 * "no GL history" to "all GL history reconstructed". It is intentionally out
 * of reach for normal finance day-to-day work.
 *
 * <ul>
 *   <li>{@code POST /backfill-journal-entries} — start a workflow (Slice 1.8a)</li>
 *   <li>{@code GET  /backfill-journal-entries/{workflowId}} — poll status (Slice 1.8b)</li>
 * </ul>
 *
 * @since Module 12, Slice 1.8a
 */
@RestController
@RequestMapping("/api/v1/admin/finance")
@Tag(name = "GL Backfill (Admin)",
     description = "Retroactive journal-entry backfill (Slice 1.8). One-time mechanism for moving from \"no GL history\" to \"all GL history reconstructed\" — walks every relevant sub-ledger source table and posts the JEs SubledgerPostingService would have written. Idempotent via the JE-gateway triple. See operations/period-end-closures-backfill.md runbook.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class BackfillAdminController {

    private final BackfillAdminService service;

    @PostMapping("/backfill-journal-entries")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Start a retroactive JE backfill workflow",
               description = "Kicks off a Temporal workflow that sweeps source tables (policies / claims / claim_expenses / endorsements / ri_fac_covers) and posts the missing JEs. Pre-flight check rejects the run if any target period is HARD_CLOSED or past its SOFT grace. PLATFORM_ADMIN role only — intentionally out of reach of normal finance day-to-day work.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Workflow started",
            content = @Content(schema = @Schema(implementation = StartBackfillResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid backfill scope (event types, date range, etc.)", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks PLATFORM_ADMIN", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Pre-flight rejected (target period is locked)", content = @Content)
    })
    public ApiResponse<StartBackfillResponse> startBackfill(@Valid @RequestBody StartBackfillRequest request) {
        return ApiResponse.success(service.startBackfill(request));
    }

    @GetMapping("/backfill-journal-entries/{workflowId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Poll workflow status",
               description = "Returns current state of the named workflow: RUNNING / COMPLETED / FAILED, per-event-type counters (posted / skipped-dup / failed), and the resulting JE-id manifest once complete.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Status snapshot",
            content = @Content(schema = @Schema(implementation = BackfillStatusResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks PLATFORM_ADMIN", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Workflow id not found", content = @Content)
    })
    public ApiResponse<BackfillStatusResponse> getStatus(@PathVariable String workflowId) {
        return ApiResponse.success(service.getStatus(workflowId));
    }
}
