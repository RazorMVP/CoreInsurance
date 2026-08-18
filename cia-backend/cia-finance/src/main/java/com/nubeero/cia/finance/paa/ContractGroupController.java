package com.nubeero.cia.finance.paa;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.paa.dto.ContractGroupSummaryResponse;
import com.nubeero.cia.finance.paa.dto.PortfolioSummaryResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only REST surface for IFRS 17 contract groups + portfolios.
 *
 * <ul>
 *   <li>{@code GET /api/v1/finance/paa/contract-groups} — list contract
 *       groups with optional filters (portfolioId / cohortYear / onerousness
 *       / status). Phase 5 frontend slice F5.11.</li>
 *   <li>{@code GET /api/v1/finance/paa/portfolios} — list every active
 *       portfolio. Drives the portfolio filter dropdown.</li>
 * </ul>
 *
 * <p>RBAC: {@code FINANCE_VIEW} — disclosure data, read by finance + audit
 * users. Writers live in {@code ContractGroupingService} (Slice 2.2,
 * event-driven only) and are not exposed via this controller.
 */
@RestController
@RequestMapping("/api/v1/finance/paa")
@Tag(name = "PAA — Contract Groups",
     description = "IFRS 17 §16-22 contract groups + portfolios. Read-only — writes are event-driven via Slice 2.2's ContractGroupingService, triggered by policy approval (direct business) and FAC accept/cede (facultative reinsurance, inward + outward). The (portfolio, cohort_year, onerousness) triple is permanent per §22.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class ContractGroupController {

    private final ContractGroupQueryService service;

    @GetMapping("/contract-groups")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "List contract groups",
               description = "All five filters are optional. Default ordering: cohort year DESC, portfolio code ASC, onerousness ASC (most recent cohort first; onerous groups at the bottom of each (portfolio, cohort) pair).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Contract groups",
            content = @Content(schema = @Schema(implementation = ContractGroupSummaryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content)
    })
    public ApiResponse<List<ContractGroupSummaryResponse>> listGroups(
        @RequestParam(required = false) UUID portfolioId,
        @RequestParam(required = false) Integer cohortYear,
        @RequestParam(required = false) Onerousness onerousness,
        @RequestParam(required = false) GroupStatus status,
        @RequestParam(required = false) ContractNature contractNature
    ) {
        return ApiResponse.success(service.listGroups(portfolioId, cohortYear, onerousness, status, contractNature));
    }

    @GetMapping("/portfolios")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "List portfolios",
               description = "Every portfolio (active + inactive) sorted by code ASC. Soft-deleted rows excluded.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Portfolios",
            content = @Content(schema = @Schema(implementation = PortfolioSummaryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ApiResponse<List<PortfolioSummaryResponse>> listPortfolios() {
        return ApiResponse.success(service.listPortfolios());
    }
}
