package com.nubeero.cia.audit.web;

import com.nubeero.cia.audit.alert.AuditAlertConfigService;
import com.nubeero.cia.audit.alert.dto.AuditAlertConfigRequest;
import com.nubeero.cia.audit.alert.dto.AuditAlertConfigResponse;
import com.nubeero.cia.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/setup/audit-config")
@RequiredArgsConstructor
@Tag(name = "Audit Configuration", description = "Alert thresholds and data retention settings — System Admin only for writes")
public class AuditAlertConfigController {

    private final AuditAlertConfigService configService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SETUP_UPDATE', 'AUDIT_VIEW')")
    @Operation(summary = "Get audit alert configuration", description = "Returns the current alert thresholds and retention policy for this tenant.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Current config"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<AuditAlertConfigResponse>> get() {
        return ResponseEntity.ok(ApiResponse.success(configService.get()));
    }

    @PutMapping
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(
        summary = "Update audit alert configuration",
        description = "Updates alert thresholds (failed logins, bulk deletes, large approval amount, business hours) and retention period. Requires SETUP_UPDATE role (System Admin only)."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Config updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — requires SETUP_UPDATE")
    })
    public ResponseEntity<ApiResponse<AuditAlertConfigResponse>> update(
            @Valid @RequestBody AuditAlertConfigRequest request) {
        return ResponseEntity.ok(ApiResponse.success(configService.update(request)));
    }
}
