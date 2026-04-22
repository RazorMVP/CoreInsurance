package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.product.dto.CommissionSetupRequest;
import com.nubeero.cia.setup.product.dto.CommissionSetupResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/setup/products/{productId}/commission-setups")
@RequiredArgsConstructor
public class CommissionSetupController {

    private final CommissionSetupService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ResponseEntity<ApiResponse<List<CommissionSetupResponse>>> list(@PathVariable UUID productId) {
        return ResponseEntity.ok(ApiResponse.success(service.listByProduct(productId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ResponseEntity<ApiResponse<CommissionSetupResponse>> get(
            @PathVariable UUID productId, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(service.get(productId, id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('SETUP_CREATE')")
    public ResponseEntity<ApiResponse<CommissionSetupResponse>> create(
            @PathVariable UUID productId, @Valid @RequestBody CommissionSetupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.create(productId, request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<CommissionSetupResponse>> update(
            @PathVariable UUID productId, @PathVariable UUID id,
            @Valid @RequestBody CommissionSetupRequest request) {
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
