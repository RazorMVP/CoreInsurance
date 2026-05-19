package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only Chart of Accounts endpoint.
 *
 * <p>Returns the COA as a nested tree rooted at the five top-level
 * account-type classes (Asset / Liability / Equity / Income / Expense),
 * with children sorted ascending by code. Soft-deleted rows are omitted.
 *
 * <p>Slice 1.3 (Module 12 — Period-End Closures). The endpoint is read-only;
 * CRUD on the COA is deferred until the post-Phase-7 tenant-customisation
 * epic.
 */
@RestController
@RequestMapping("/api/v1/finance/chart-of-accounts")
@RequiredArgsConstructor
public class ChartOfAccountController {

    private final ChartOfAccountService service;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<List<ChartOfAccountNode>> getTree() {
        return ApiResponse.success(service.getTree());
    }
}
