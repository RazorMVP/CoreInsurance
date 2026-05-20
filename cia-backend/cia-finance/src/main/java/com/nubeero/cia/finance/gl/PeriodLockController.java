package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.dto.ClosePeriodRequest;
import com.nubeero.cia.finance.dto.PeriodLockResponse;
import com.nubeero.cia.finance.dto.ReopenPeriodRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * REST surface for the period-lock lifecycle (Slice 1.7).
 *
 * <ul>
 *   <li>{@code POST /api/v1/finance/period-locks/{periodId}/soft-close}
 *       — flip a period to SOFT_CLOSED, opening the grace window. Idempotent.</li>
 *   <li>{@code POST /api/v1/finance/period-locks/{periodId}/hard-close}
 *       — flip to HARD_CLOSED (auto-soft first if needed to satisfy
 *       {@code ck_fiscal_period_close_chronology}). Idempotent.</li>
 *   <li>{@code POST /api/v1/finance/period-locks/{periodId}/reopen}
 *       — release the active HARD lock; publishes {@link PeriodReopenedEvent}.</li>
 *   <li>{@code GET  /api/v1/finance/period-locks/{periodId}/history}
 *       — full audit trail of soft/hard/release events for the period.</li>
 *   <li>{@code GET  /api/v1/finance/period-locks/preview?from=...&to=...}
 *       — bulk-preview report so a workflow can pre-check a date range.</li>
 * </ul>
 *
 * <p>RBAC:
 * <ul>
 *   <li>Soft / hard close: {@code FINANCE_APPROVE} (same bar as
 *       fiscal-year close in {@link FiscalYearController}).</li>
 *   <li>Reopen: {@code FINANCE_REOPEN_PERIOD} — separate role, narrower
 *       distribution. CFO / Finance Director level by default.</li>
 *   <li>History / preview: {@code FINANCE_VIEW}.</li>
 * </ul>
 *
 * @since Module 12, Slice 1.7
 */
@RestController
@RequestMapping("/api/v1/finance/period-locks")
@Tag(name = "Period Locks",
     description = "Period-lock lifecycle: SOFT_CLOSED (with 5-business-day grace window) → HARD_CLOSED → REOPENED. Hibernate PeriodLockInterceptor enforces locks at every write to LockableByPeriod entities — see Slice 1.7. Locked-period writes throw HTTP 423 LOCKED with structured meta.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class PeriodLockController {

    private final PeriodLockService service;

    @PostMapping("/{periodId}/soft-close")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(
        summary = "Soft-close a period",
        description = "Flips a period to SOFT_CLOSED and opens the 5-business-day grace window. Reads + reversals continue to flow; new writes require FINANCE_OVERRIDE_LOCK. Idempotent — repeated soft-close on an already SOFT_CLOSED period returns the existing active lock.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Period soft-closed",
            content = @Content(schema = @Schema(implementation = PeriodLockResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Period not found", content = @Content)
    })
    public ApiResponse<PeriodLockResponse> softClose(@PathVariable UUID periodId,
                                                     @Valid @RequestBody ClosePeriodRequest request) {
        return ApiResponse.success(PeriodLockResponse.from(service.softClose(periodId, request.reason())));
    }

    @PostMapping("/{periodId}/hard-close")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(
        summary = "Hard-close a period",
        description = "Flips a period to HARD_CLOSED. If the period is still OPEN, auto-soft-closes first to satisfy the V31 ck_fiscal_period_close_chronology check constraint. After HARD close, ALL writes (including reversals) are blocked except via FINANCE_REOPEN_PERIOD reopen. Idempotent.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Period hard-closed",
            content = @Content(schema = @Schema(implementation = PeriodLockResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Period not found", content = @Content)
    })
    public ApiResponse<PeriodLockResponse> hardClose(@PathVariable UUID periodId,
                                                     @Valid @RequestBody ClosePeriodRequest request) {
        return ApiResponse.success(PeriodLockResponse.from(service.hardClose(periodId, request.reason())));
    }

    @PostMapping("/{periodId}/reopen")
    @PreAuthorize("hasRole('FINANCE_REOPEN_PERIOD')")
    @Operation(
        summary = "Reopen a closed period",
        description = "Releases the active SOFT or HARD lock. Publishes PeriodReopenedEvent → CFO email notification (recipients from cia.finance.period-reopen-recipients property). Requires FINANCE_REOPEN_PERIOD role (segregation of duties — distinct from FINANCE_APPROVE that closes).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Period reopened",
            content = @Content(schema = @Schema(implementation = PeriodLockResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_REOPEN_PERIOD", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Period not found, or no active lock", content = @Content)
    })
    public ApiResponse<PeriodLockResponse> reopen(@PathVariable UUID periodId,
                                                  @Valid @RequestBody ReopenPeriodRequest request) {
        return ApiResponse.success(PeriodLockResponse.from(service.reopen(periodId, request.reason())));
    }

    @GetMapping("/{periodId}/history")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(
        summary = "Get the full lock history of a period",
        description = "Returns every soft/hard/release event for the period in chronological order. The period_lock table is a Type-2 SCD — released_at IS NULL identifies the current active lock; older rows are the audit trail.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Lock history (chronological)",
            content = @Content(schema = @Schema(implementation = PeriodLockResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content)
    })
    public ApiResponse<List<PeriodLockResponse>> history(@PathVariable UUID periodId) {
        return ApiResponse.success(service.history(periodId).stream().map(PeriodLockResponse::from).toList());
    }

    @GetMapping("/preview")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(
        summary = "Bulk-preview lock state across a date range",
        description = "Returns one LockReportEntry per business date in [from, to]. Used by Slice 1.8 backfill and Module 8 bulk receipts to pre-flight a range before kicking off the workflow — surfaces locks BEFORE the per-row write fails rather than discovering them on row 4,837.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "One entry per business date in range"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Date range invalid (from > to or missing)", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content)
    })
    public ApiResponse<List<LockReportEntry>> preview(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(service.previewLock(from, to));
    }
}
