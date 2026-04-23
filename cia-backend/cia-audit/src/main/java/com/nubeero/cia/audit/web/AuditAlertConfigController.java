package com.nubeero.cia.audit.web;

import com.nubeero.cia.audit.alert.AuditAlertConfigService;
import com.nubeero.cia.audit.alert.dto.AuditAlertConfigRequest;
import com.nubeero.cia.audit.alert.dto.AuditAlertConfigResponse;
import com.nubeero.cia.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/setup/audit-config")
@RequiredArgsConstructor
public class AuditAlertConfigController {

    private final AuditAlertConfigService configService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SETUP_UPDATE', 'AUDIT_VIEW')")
    public ResponseEntity<ApiResponse<AuditAlertConfigResponse>> get() {
        return ResponseEntity.ok(ApiResponse.success(configService.get()));
    }

    @PutMapping
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<AuditAlertConfigResponse>> update(
            @Valid @RequestBody AuditAlertConfigRequest request) {
        return ResponseEntity.ok(ApiResponse.success(configService.update(request)));
    }
}
