package com.nubeero.cia.audit.web;

import com.nubeero.cia.audit.log.dto.AuditLogResponse;
import com.nubeero.cia.audit.login.dto.LoginAuditLogResponse;
import com.nubeero.cia.audit.report.AuditReportService;
import com.nubeero.cia.audit.report.dto.UserActivitySummary;
import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
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
public class AuditReportController {

    private final AuditReportService reportService;

    /** Report 1: All actions by a specific user in a date range. */
    @GetMapping("/actions-by-user")
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<Page<AuditLogResponse>>> actionsByUser(
            @RequestParam String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AuditLogResponse> page = reportService.actionsByUser(userId, from, to, pageable);
        return ResponseEntity.ok(ApiResponse.success(page, buildMeta(page)));
    }

    /** Report 2: All actions within a specific module (entity type). */
    @GetMapping("/actions-by-module")
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<Page<AuditLogResponse>>> actionsByModule(
            @RequestParam String entityType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AuditLogResponse> page = reportService.actionsByModule(entityType, from, to, pageable);
        return ResponseEntity.ok(ApiResponse.success(page, buildMeta(page)));
    }

    /** Report 3: All approvals and rejections across all modules. */
    @GetMapping("/approvals")
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<Page<AuditLogResponse>>> approvalAuditTrail(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AuditLogResponse> page = reportService.approvalAuditTrail(from, to, pageable);
        return ResponseEntity.ok(ApiResponse.success(page, buildMeta(page)));
    }

    /** Report 4: Full change history for a specific entity. */
    @GetMapping("/data-changes")
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<Page<AuditLogResponse>>> dataChanges(
            @RequestParam String entityType,
            @RequestParam String entityId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AuditLogResponse> page = reportService.dataChanges(entityType, entityId, pageable);
        return ResponseEntity.ok(ApiResponse.success(page, buildMeta(page)));
    }

    /** Report 5: Login and security events in a date range. */
    @GetMapping("/login-security")
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<Page<LoginAuditLogResponse>>> loginSecurityReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<LoginAuditLogResponse> page = reportService.loginSecurityReport(from, to, pageable);
        return ResponseEntity.ok(ApiResponse.success(page, buildMeta(page)));
    }

    /** Report 6: Ranked user activity summary for a date range. */
    @GetMapping("/user-activity")
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
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
