package com.nubeero.cia.finance.paa;

import com.nubeero.cia.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST surface for PAA period close (Slice 2.5).
 *
 * <ul>
 *   <li>{@code POST /api/v1/finance/paa/period-close/{periodId}} — runs LRC
 *       + LIC engines for the period and returns the §83 / §84
 *       Insurance Service Result alongside the engine outputs. Idempotent:
 *       engines that have already run for the period are skipped.</li>
 *   <li>{@code GET  /api/v1/finance/paa/insurance-service-result/{periodId}}
 *       — recomputes the §83 / §84 disclosure view from the paa_lrc +
 *       paa_lic state (no engine invocation). Read-only.</li>
 * </ul>
 *
 * <p>RBAC: {@code FINANCE_APPROVE} for close (same bar as PPA, period
 * close, LRC/LIC recognition); {@code FINANCE_VIEW} for the read-only
 * service-result GET.
 */
@RestController
@RequestMapping("/api/v1/finance/paa")
@RequiredArgsConstructor
public class PaaPeriodCloseController {

    private final PaaPeriodCloseService periodCloseService;
    private final InsuranceServiceResultService insuranceServiceResultService;

    @PostMapping("/period-close/{periodId}")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<PaaPeriodCloseResult> closePeriod(@PathVariable UUID periodId) {
        return ApiResponse.success(periodCloseService.closePeriod(periodId));
    }

    @GetMapping("/insurance-service-result/{periodId}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<InsuranceServiceResult> insuranceServiceResult(@PathVariable UUID periodId) {
        return ApiResponse.success(insuranceServiceResultService.compute(periodId));
    }
}
