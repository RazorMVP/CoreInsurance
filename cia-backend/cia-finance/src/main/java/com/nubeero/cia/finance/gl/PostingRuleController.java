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
 * Read-only Posting Rules endpoint.
 *
 * <p>Returns the rules that {@code SubledgerPostingService}'s table-driven
 * dispatch path uses to translate sub-ledger events (POLICY_APPROVED,
 * CLAIM_APPROVED, etc.) into journal-entry lines. Six rules ship in V33;
 * compound events ({@code FAC_PREMIUM_CEDED}, 3-line) are intentionally
 * absent because their shape doesn't fit one row, and are hard-coded in
 * the service.
 *
 * <p>Slice F5.7 (Module 12 — Period-End Closures frontend). The endpoint
 * is read-only; rule mutations are deferred until the post-Phase-7
 * tenant-customisation epic (same status as the COA).
 */
@RestController
@RequestMapping("/api/v1/finance/posting-rules")
@Tag(name = "Posting Rules",
     description = "Read-only access to the sub-ledger → journal-entry posting rules used by SubledgerPostingService. Seeded by V33 (6 rules); SYSTEM rows are immutable until the post-Phase-7 tenant-customisation epic. Compound multi-line events (FAC_PREMIUM_CEDED) are hard-coded in the service and intentionally absent here.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class PostingRuleController {

    private final PostingRuleService postingRuleService;
    private final ChartOfAccountService chartOfAccountService;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "List all posting rules",
               description = "Returns every non-soft-deleted rule, sorted ascending by source_event_type. Each row carries the Dr/Cr account codes plus their human-readable names resolved through the Chart of Accounts.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Posting rules",
            content = @Content(schema = @Schema(implementation = PostingRuleResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content)
    })
    public ApiResponse<List<PostingRuleResponse>> list() {
        return ApiResponse.success(postingRuleService.findAll().stream()
            .map(rule -> PostingRuleResponse.from(rule,
                code -> chartOfAccountService.findByCode(code).getName()))
            .toList());
    }
}
