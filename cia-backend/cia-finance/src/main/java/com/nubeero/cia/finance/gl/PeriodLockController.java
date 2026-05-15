package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.dto.ClosePeriodRequest;
import com.nubeero.cia.finance.dto.PeriodLockResponse;
import com.nubeero.cia.finance.dto.ReopenPeriodRequest;
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
@RequiredArgsConstructor
public class PeriodLockController {

    private final PeriodLockService service;

    @PostMapping("/{periodId}/soft-close")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<PeriodLockResponse> softClose(@PathVariable UUID periodId,
                                                     @Valid @RequestBody ClosePeriodRequest request) {
        return ApiResponse.success(PeriodLockResponse.from(service.softClose(periodId, request.reason())));
    }

    @PostMapping("/{periodId}/hard-close")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<PeriodLockResponse> hardClose(@PathVariable UUID periodId,
                                                     @Valid @RequestBody ClosePeriodRequest request) {
        return ApiResponse.success(PeriodLockResponse.from(service.hardClose(periodId, request.reason())));
    }

    @PostMapping("/{periodId}/reopen")
    @PreAuthorize("hasRole('FINANCE_REOPEN_PERIOD')")
    public ApiResponse<PeriodLockResponse> reopen(@PathVariable UUID periodId,
                                                  @Valid @RequestBody ReopenPeriodRequest request) {
        return ApiResponse.success(PeriodLockResponse.from(service.reopen(periodId, request.reason())));
    }

    @GetMapping("/{periodId}/history")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<List<PeriodLockResponse>> history(@PathVariable UUID periodId) {
        return ApiResponse.success(service.history(periodId).stream().map(PeriodLockResponse::from).toList());
    }

    @GetMapping("/preview")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<List<LockReportEntry>> preview(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(service.previewLock(from, to));
    }
}
