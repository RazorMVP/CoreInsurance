package com.nubeero.cia.setup.quote;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.quote.dto.AdjustmentTypeRequest;
import com.nubeero.cia.setup.quote.dto.AdjustmentTypeResponse;
import com.nubeero.cia.setup.quote.dto.QuoteConfigRequest;
import com.nubeero.cia.setup.quote.dto.QuoteConfigResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class QuoteConfigController {

    private final QuoteConfigService service;

    // ── Quote Config ──────────────────────────────────────────────────────────

    @GetMapping("/api/v1/setup/quote-config")
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ResponseEntity<ApiResponse<QuoteConfigResponse>> getConfig() {
        return ResponseEntity.ok(ApiResponse.success(service.getConfig()));
    }

    @PutMapping("/api/v1/setup/quote-config")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<QuoteConfigResponse>> updateConfig(
            @Valid @RequestBody QuoteConfigRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.updateConfig(request)));
    }

    // ── Discount types ────────────────────────────────────────────────────────

    @GetMapping("/api/v1/setup/quote-discount-types")
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ResponseEntity<ApiResponse<List<AdjustmentTypeResponse>>> listDiscountTypes() {
        return ResponseEntity.ok(ApiResponse.success(service.listDiscountTypes()));
    }

    @PostMapping("/api/v1/setup/quote-discount-types")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<AdjustmentTypeResponse>> createDiscountType(
            @Valid @RequestBody AdjustmentTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.createDiscountType(request)));
    }

    @PutMapping("/api/v1/setup/quote-discount-types/{id}")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<AdjustmentTypeResponse>> updateDiscountType(
            @PathVariable UUID id,
            @Valid @RequestBody AdjustmentTypeRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.updateDiscountType(id, request)));
    }

    @DeleteMapping("/api/v1/setup/quote-discount-types/{id}")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<Void>> deleteDiscountType(@PathVariable UUID id) {
        service.deleteDiscountType(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── Loading types ─────────────────────────────────────────────────────────

    @GetMapping("/api/v1/setup/quote-loading-types")
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ResponseEntity<ApiResponse<List<AdjustmentTypeResponse>>> listLoadingTypes() {
        return ResponseEntity.ok(ApiResponse.success(service.listLoadingTypes()));
    }

    @PostMapping("/api/v1/setup/quote-loading-types")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<AdjustmentTypeResponse>> createLoadingType(
            @Valid @RequestBody AdjustmentTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.createLoadingType(request)));
    }

    @PutMapping("/api/v1/setup/quote-loading-types/{id}")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<AdjustmentTypeResponse>> updateLoadingType(
            @PathVariable UUID id,
            @Valid @RequestBody AdjustmentTypeRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.updateLoadingType(id, request)));
    }

    @DeleteMapping("/api/v1/setup/quote-loading-types/{id}")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<Void>> deleteLoadingType(@PathVariable UUID id) {
        service.deleteLoadingType(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
