package com.nubeero.cia.compliance.dsar;

import com.nubeero.cia.common.tenant.TenantContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers/{id}/dsar-export")
@RequiredArgsConstructor
public class DsarExportController {

    private final DsarExportService service;

    @GetMapping
    @PreAuthorize("hasRole('DATA_PROTECTION')")
    public ResponseEntity<byte[]> export(@PathVariable UUID id,
                                         @RequestParam(required = false) String format,
                                         @AuthenticationPrincipal Jwt jwt) {
        String tenantId = TenantContext.getTenantId();
        String actor = jwt != null ? jwt.getClaimAsString("preferred_username") : "system";

        if ("json".equalsIgnoreCase(format)) {
            return file(service.renderJson(id, actor), "dsar-" + id + ".json", MediaType.APPLICATION_JSON);
        }
        if ("pdf".equalsIgnoreCase(format)) {
            return file(service.renderPdf(id, actor), "dsar-" + id + ".pdf", MediaType.APPLICATION_PDF);
        }
        byte[] zip = service.exportZip(tenantId, id, actor);
        return file(zip, "dsar-" + id + ".zip", MediaType.parseMediaType("application/zip"));
    }

    private ResponseEntity<byte[]> file(byte[] body, String filename, MediaType type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(type)
                .body(body);
    }
}
