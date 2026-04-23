package com.nubeero.cia.audit.web;

import com.nubeero.cia.audit.export.AuditExportService;
import com.nubeero.cia.audit.log.dto.AuditLogFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/audit/export")
@RequiredArgsConstructor
@Tag(name = "Audit Export", description = "Export audit logs as CSV with optional filters")
public class AuditExportController {

    private final AuditExportService exportService;

    @GetMapping(produces = "text/csv")
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    @Operation(
        summary = "Export audit logs as CSV",
        description = "Streams a CSV file containing audit log entries matching the supplied filters. " +
            "All filters are optional; omitting them exports the full audit log. " +
            "Response includes `Content-Disposition: attachment` header."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "CSV file stream"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient role")
    })
    public ResponseEntity<StreamingResponseBody> exportCsv(AuditLogFilter filter) {
        String filename = "audit-export-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(exportService.exportCsv(filter));
    }
}
