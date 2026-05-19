package com.nubeero.cia.finance.paa;

import com.nubeero.cia.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST surface for the IFRS 17 §103 movement analysis (Slice 2.8).
 *
 * <p>{@code GET /api/v1/finance/paa/movement-analysis/{periodId}} returns
 * the full §103-shaped roll-forward for the period — LRC totals, LIC
 * totals, per-(portfolio × cohort × onerousness) breakdown.
 *
 * <p>Read-only. RBAC: {@code FINANCE_VIEW} (same narrower bar as the
 * Insurance Service Result read endpoint — disclosure data should be
 * widely consultable within finance/audit teams).
 */
@RestController
@RequestMapping("/api/v1/finance/paa/movement-analysis")
@RequiredArgsConstructor
public class PaaMovementAnalysisController {

    private final MovementAnalysisService service;

    @GetMapping("/{periodId}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<MovementAnalysis> get(@PathVariable UUID periodId) {
        return ApiResponse.success(service.compute(periodId));
    }
}
