package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.product.dto.SurveyThresholdRequest;
import com.nubeero.cia.setup.product.dto.SurveyThresholdResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/setup/products/{productId}/survey-thresholds")
@RequiredArgsConstructor
public class SurveyThresholdController {

    private final SurveyThresholdService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ResponseEntity<ApiResponse<List<SurveyThresholdResponse>>> list(
            @PathVariable UUID productId) {
        return ResponseEntity.ok(ApiResponse.success(service.listByProduct(productId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ResponseEntity<ApiResponse<SurveyThresholdResponse>> get(
            @PathVariable UUID productId, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(service.get(productId, id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('SETUP_CREATE')")
    public ResponseEntity<ApiResponse<SurveyThresholdResponse>> create(
            @PathVariable UUID productId, @Valid @RequestBody SurveyThresholdRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.create(productId, request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<SurveyThresholdResponse>> update(
            @PathVariable UUID productId, @PathVariable UUID id,
            @Valid @RequestBody SurveyThresholdRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.update(productId, id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID productId, @PathVariable UUID id) {
        service.delete(productId, id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
