package com.nubeero.cia.finance.audit;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/finance/pdf-downloads")
@Tag(name = "PDF Download Log (Module 8)",
     description = "Server-side per-user history of receipt + payment PDF downloads. Separate from audit_log to keep compliance auditing clean. 30-day retention enforced by a weekly Temporal cron.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class PdfDownloadLogController {

    private final PdfDownloadLogService service;

    @GetMapping
    @PreAuthorize("hasAuthority('FINANCE_VIEW')")
    @Operation(summary = "List recent PDF downloads for the calling user",
               description = "Returns the calling user's PDF download events from the last `days` days, newest first. Default 1 day (today); max 30 days.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Recent downloads",
            content = @Content(schema = @Schema(implementation = PdfDownloadLogResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content)
    })
    public ApiResponse<List<PdfDownloadLogResponse>> list(
            @RequestParam(defaultValue = "1") int days) {
        int boundedDays = Math.max(1, Math.min(days, 30));
        return ApiResponse.success(service.listForUser(boundedDays, 50));
    }
}
