package com.nubeero.cia.reports.controller;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.reports.controller.dto.*;
import com.nubeero.cia.reports.domain.ReportAccessPolicy;
import com.nubeero.cia.reports.domain.ReportCategory;
import com.nubeero.cia.reports.domain.ReportDefinition;
import com.nubeero.cia.reports.service.ReportAccessService;
import com.nubeero.cia.reports.service.ReportDefinitionService;
import com.nubeero.cia.reports.service.ReportRunnerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(name = "Reports & Analytics", description = "55 pre-built reports, custom report builder, CSV/PDF export, pin management, access control")
public class ReportController {

    private final ReportDefinitionService definitionService;
    private final ReportRunnerService runnerService;
    private final ReportAccessService accessService;

    // ── Report definitions ─────────────────────────────────────────────

    @GetMapping("/definitions")
    @PreAuthorize("hasAuthority('reports:view')")
    @Operation(summary = "List all accessible reports")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "List of report definitions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<List<ReportDefinitionDto>>> listDefinitions(
            @RequestParam(required = false) ReportCategory category) {
        List<ReportDefinition> list = category != null
                ? definitionService.listByCategory(category)
                : definitionService.listAll();
        return ResponseEntity.ok(ApiResponse.success(
                list.stream().map(ReportDefinitionDto::from).toList()));
    }

    @GetMapping("/definitions/{id}")
    @PreAuthorize("hasAuthority('reports:view')")
    @Operation(summary = "Get a single report definition")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Report definition"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<ReportDefinitionDto>> getDefinition(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(
                ReportDefinitionDto.from(definitionService.get(id))));
    }

    @PostMapping("/definitions")
    @PreAuthorize("hasAuthority('reports:create_custom')")
    @Operation(summary = "Create a custom report definition")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Created"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<ReportDefinitionDto>> createDefinition(
            @Valid @RequestBody CreateReportRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.success(
                ReportDefinitionDto.from(definitionService.create(request))));
    }

    @PutMapping("/definitions/{id}")
    @PreAuthorize("hasAuthority('reports:create_custom')")
    @Operation(summary = "Update a custom report definition")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<ReportDefinitionDto>> updateDefinition(
            @PathVariable UUID id,
            @Valid @RequestBody CreateReportRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                ReportDefinitionDto.from(definitionService.update(id, request))));
    }

    @DeleteMapping("/definitions/{id}")
    @PreAuthorize("hasAuthority('reports:create_custom')")
    @Operation(summary = "Delete a custom report definition")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<Void> deleteDefinition(@PathVariable UUID id) {
        definitionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/definitions/{id}/clone")
    @PreAuthorize("hasAuthority('reports:create_custom')")
    @Operation(summary = "Clone a SYSTEM report into a CUSTOM report")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Cloned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<ReportDefinitionDto>> cloneDefinition(
            @PathVariable UUID id,
            @RequestParam(required = false) String name) {
        return ResponseEntity.status(201).body(ApiResponse.success(
                ReportDefinitionDto.from(definitionService.clone(id, name))));
    }

    // ── Run endpoints ──────────────────────────────────────────────────

    @PostMapping("/run")
    @PreAuthorize("hasAuthority('reports:view')")
    @Operation(summary = "Run a report — returns JSON result")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Report result"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<ReportResultDto>> runReport(
            @Valid @RequestBody ReportRunRequest request) {
        return ResponseEntity.ok(ApiResponse.success(runnerService.run(request)));
    }

    @PostMapping("/run/csv")
    @PreAuthorize("hasAuthority('reports:export_csv')")
    @Operation(summary = "Run a report — streams CSV download")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "CSV file"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<StreamingResponseBody> runCsv(
            @Valid @RequestBody ReportRunRequest request) {
        String filename = "report-" + LocalDate.now() + ".csv";
        StreamingResponseBody body = runnerService.runCsv(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(body);
    }

    @PostMapping("/run/pdf")
    @PreAuthorize("hasAuthority('reports:export_pdf')")
    @Operation(summary = "Run a report — returns PDF download")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "PDF file"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<byte[]> runPdf(@Valid @RequestBody ReportRunRequest request) {
        byte[] pdf = runnerService.runPdf(request);
        if (pdf == null) {
            return ResponseEntity.internalServerError().build();
        }
        String filename = "report-" + LocalDate.now() + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    // ── Pin management ─────────────────────────────────────────────────

    @GetMapping("/pins")
    @PreAuthorize("hasAuthority('reports:view')")
    @Operation(summary = "List pinned reports for the current user")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Pinned reports"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<List<ReportDefinitionDto>>> listPins(
            @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        List<ReportDefinitionDto> pinned = runnerService.listPinned(userId)
                .stream().map(ReportDefinitionDto::from).toList();
        return ResponseEntity.ok(ApiResponse.success(pinned));
    }

    @PostMapping("/pins/{id}")
    @PreAuthorize("hasAuthority('reports:view')")
    @Operation(summary = "Pin a report for the current user")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Pinned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<Void> pinReport(@PathVariable UUID id,
                                          @AuthenticationPrincipal Jwt jwt) {
        runnerService.pin(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/pins/{id}")
    @PreAuthorize("hasAuthority('reports:view')")
    @Operation(summary = "Unpin a report for the current user")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Unpinned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<Void> unpinReport(@PathVariable UUID id,
                                            @AuthenticationPrincipal Jwt jwt) {
        runnerService.unpin(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }

    // ── Access policy management ───────────────────────────────────────

    @GetMapping("/access-policies")
    @PreAuthorize("hasAuthority('reports:manage_access')")
    @Operation(summary = "List all access policies (System Admin only)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Access policies"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<ApiResponse<List<ReportAccessPolicy>>> listAccessPolicies(
            @RequestParam UUID accessGroupId) {
        return ResponseEntity.ok(ApiResponse.success(
                accessService.listByGroup(accessGroupId)));
    }

    @PutMapping("/access-policies")
    @PreAuthorize("hasAuthority('reports:manage_access')")
    @Operation(summary = "Upsert a category- or report-level access policy")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Saved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<ApiResponse<ReportAccessPolicy>> upsertAccessPolicy(
            @Valid @RequestBody AccessPolicyUpdateRequest req) {
        ReportAccessPolicy policy = accessService.upsert(
                req.getAccessGroupId(), req.getCategory(), req.getReportId(),
                req.isCanView(), req.isCanExportCsv(), req.isCanExportPdf());
        return ResponseEntity.ok(ApiResponse.success(policy));
    }
}
