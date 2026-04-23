package com.nubeero.cia.audit.web;

import com.nubeero.cia.audit.log.dto.AuditLogResponse;
import com.nubeero.cia.audit.login.dto.LoginAuditLogResponse;
import com.nubeero.cia.audit.report.AuditReportService;
import com.nubeero.cia.audit.report.dto.UserActivitySummary;
import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/audit/reports")
@RequiredArgsConstructor
@Tag(name = "Audit Reports", description = "Six pre-built compliance reports — user activity, module actions, approvals, data changes, login security, and ranked user summary")
public class AuditReportController {

    private final AuditReportService reportService;

    @GetMapping("/actions-by-user")
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    @Operation(summary = "Report 1 — Actions by user", description = "All audit events performed by a specific user in a date range.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Paginated audit events for user"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<Page<AuditLogResponse>>> actionsByUser(
            @RequestParam String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AuditLogResponse> page = reportService.actionsByUser(userId, from, to, pageable);
        return ResponseEntity.ok(ApiResponse.success(page, buildMeta(page)));
    }

    @GetMapping("/actions-by-module")
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    @Operation(summary = "Report 2 — Actions by module", description = "All audit events for a specific entity type (module) in a date range.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Paginated audit events for module"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<Page<AuditLogResponse>>> actionsByModule(
            @RequestParam String entityType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AuditLogResponse> page = reportService.actionsByModule(entityType, from, to, pageable);
        return ResponseEntity.ok(ApiResponse.success(page, buildMeta(page)));
    }

    @GetMapping("/approvals")
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    @Operation(summary = "Report 3 — Approval audit trail", description = "All APPROVE and REJECT events across every module in a date range.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Paginated approval events"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<Page<AuditLogResponse>>> approvalAuditTrail(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AuditLogResponse> page = reportService.approvalAuditTrail(from, to, pageable);
        return ResponseEntity.ok(ApiResponse.success(page, buildMeta(page)));
    }

    @GetMapping("/data-changes")
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    @Operation(summary = "Report 4 — Data change history", description = "Full before/after change history for a specific entity.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Paginated change events"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<Page<AuditLogResponse>>> dataChanges(
            @RequestParam String entityType,
            @RequestParam String entityId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AuditLogResponse> page = reportService.dataChanges(entityType, entityId, pageable);
        return ResponseEntity.ok(ApiResponse.success(page, buildMeta(page)));
    }

    @GetMapping("/login-security")
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    @Operation(summary = "Report 5 — Login & security events", description = "Login, logout, and failed authentication events in a date range.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Paginated login events"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<Page<LoginAuditLogResponse>>> loginSecurityReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<LoginAuditLogResponse> page = reportService.loginSecurityReport(from, to, pageable);
        return ResponseEntity.ok(ApiResponse.success(page, buildMeta(page)));
    }

    @GetMapping("/user-activity")
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    @Operation(summary = "Report 6 — User activity summary", description = "Ranked list of users by total action count in a date range.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ranked user activity list"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<List<UserActivitySummary>>> userActivitySummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(ApiResponse.success(reportService.userActivitySummary(from, to)));
    }

    private <T> ApiMeta buildMeta(Page<T> page) {
        return ApiMeta.builder()
                .total(page.getTotalElements())
                .page(page.getNumber())
                .size(page.getSize())
                .build();
    }
}
