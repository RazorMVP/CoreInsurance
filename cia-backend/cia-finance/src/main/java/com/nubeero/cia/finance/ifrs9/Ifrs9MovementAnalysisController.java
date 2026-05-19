package com.nubeero.cia.finance.ifrs9;

import com.nubeero.cia.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST surface for the IFRS 9 §B5.5.39 movement analysis (Slice 3.7).
 *
 * <p>{@code GET /api/v1/finance/ifrs9/movement-analysis/{periodId}}
 * returns the full §B5.5.39 disclosure for the period: per-holding
 * investment roll-forward + premium-receivable ECL aggregate.
 *
 * <p>RBAC: {@code FINANCE_VIEW} — read-only disclosure access, mirrors
 * Slice 2.8's narrower bar for disclosure GET endpoints.
 */
@RestController
@RequestMapping("/api/v1/finance/ifrs9/movement-analysis")
@RequiredArgsConstructor
public class Ifrs9MovementAnalysisController {

    private final Ifrs9MovementAnalysisService service;

    @GetMapping("/{periodId}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<Ifrs9MovementAnalysis> get(@PathVariable UUID periodId) {
        return ApiResponse.success(service.compute(periodId));
    }
}
