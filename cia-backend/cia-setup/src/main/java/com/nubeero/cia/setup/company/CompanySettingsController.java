package com.nubeero.cia.setup.company;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.company.dto.CompanySettingsRequest;
import com.nubeero.cia.setup.company.dto.CompanySettingsResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/setup/company-settings")
@RequiredArgsConstructor
public class CompanySettingsController {

    private final CompanySettingsService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ResponseEntity<ApiResponse<CompanySettingsResponse>> get() {
        return ResponseEntity.ok(ApiResponse.success(service.get()));
    }

    @PutMapping
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<CompanySettingsResponse>> upsert(
            @Valid @RequestBody CompanySettingsRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.upsert(request)));
    }
}
