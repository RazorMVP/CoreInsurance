package com.nubeero.cia.audit.web;

import com.nubeero.cia.audit.log.AuditQueryService;
import com.nubeero.cia.audit.log.dto.AuditLogFilter;
import com.nubeero.cia.audit.log.dto.AuditLogResponse;
import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit/logs")
@RequiredArgsConstructor
@Tag(name = "Audit Logs", description = "Search and view the full system audit trail across all modules")
public class AuditLogController {

    private final AuditQueryService queryService;

    @GetMapping
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    @Operation(
        summary = "Search audit logs",
        description = "Paginated, filterable audit trail. Filter by entityType, entityId, userId, action, or date range."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Paginated audit log entries"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient role — requires AUDIT_VIEW or SETUP_UPDATE")
    })
    public ResponseEntity<ApiResponse<Page<AuditLogResponse>>> search(
            AuditLogFilter filter,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AuditLogResponse> page = queryService.search(filter, pageable);
        ApiMeta meta = ApiMeta.builder()
                .total(page.getTotalElements())
                .page(page.getNumber())
                .size(page.getSize())
                .build();
        return ResponseEntity.ok(ApiResponse.success(page, meta));
    }
}
