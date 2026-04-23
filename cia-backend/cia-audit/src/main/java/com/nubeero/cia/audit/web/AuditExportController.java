package com.nubeero.cia.audit.web;

import com.nubeero.cia.audit.export.AuditExportService;
import com.nubeero.cia.audit.log.dto.AuditLogFilter;
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
public class AuditExportController {

    private final AuditExportService exportService;

    @GetMapping(produces = "text/csv")
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    public ResponseEntity<StreamingResponseBody> exportCsv(AuditLogFilter filter) {
        String filename = "audit-export-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(exportService.exportCsv(filter));
    }
}
