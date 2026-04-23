package com.nubeero.cia.audit.web;

import com.nubeero.cia.audit.alert.AuditAlertService;
import com.nubeero.cia.audit.alert.dto.AuditAlertResponse;
import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit/alerts")
@RequiredArgsConstructor
public class AuditAlertController {

    private final AuditAlertService alertService;

    @GetMapping
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<Page<AuditAlertResponse>>> listAll(
            @RequestParam(defaultValue = "false") boolean unacknowledgedOnly,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AuditAlertResponse> page = unacknowledgedOnly
                ? alertService.listUnacknowledged(pageable)
                : alertService.listAll(pageable);
        ApiMeta meta = ApiMeta.builder()
                .total(page.getTotalElements()).page(page.getNumber()).size(page.getSize()).build();
        return ResponseEntity.ok(ApiResponse.success(page, meta));
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<AuditAlertResponse>> acknowledge(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(alertService.acknowledge(id)));
    }
}
