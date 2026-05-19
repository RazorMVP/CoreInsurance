package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.dto.TrialBalanceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Trial balance REST endpoint.
 *
 * <p>{@code GET /api/v1/finance/trial-balance?asOf=YYYY-MM-DD} —
 * D4=A interprets {@code asOf} as business date (economic date) and
 * D3=A returns a flat per-account list with a footer summary.
 *
 * <p>RBAC: {@code FINANCE_VIEW}. The trial balance leaks no PII so a
 * single read-permission gates it.
 */
@RestController
@RequestMapping("/api/v1/finance/trial-balance")
@RequiredArgsConstructor
public class TrialBalanceController {

    private final TrialBalanceService service;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<TrialBalanceResponse> get(
        @RequestParam("asOf") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.success(service.trialBalanceAsOf(asOf));
    }
}
