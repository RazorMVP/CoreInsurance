package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Chart of Accounts",
     description = "Read-only access to the per-tenant Chart of Accounts. The COA is seeded by Flyway (V32 — 129 rows, 3-level hierarchy, IFRS-17 + IFRS-9 + NAICOM role tags). CRUD is deferred until the post-Phase-7 tenant-customisation epic.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class ChartOfAccountController {

    private final ChartOfAccountService service;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "Get the Chart of Accounts as a nested tree",
               description = "Returns the COA rooted at the five top-level account-type classes (ASSET / LIABILITY / EQUITY / INCOME / EXPENSE), with children sorted ascending by code. Soft-deleted rows are omitted.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "COA tree",
            content = @Content(schema = @Schema(implementation = ChartOfAccountNode.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content)
    })
    public ApiResponse<List<ChartOfAccountNode>> getTree() {
        return ApiResponse.success(service.getTree());
    }
}
