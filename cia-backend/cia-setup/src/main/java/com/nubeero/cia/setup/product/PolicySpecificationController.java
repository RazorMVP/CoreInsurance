package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.product.dto.PolicySpecificationRequest;
import com.nubeero.cia.setup.product.dto.PolicySpecificationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/setup/products/{productId}/policy-specification")
@RequiredArgsConstructor
public class PolicySpecificationController {

    private final PolicySpecificationService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ResponseEntity<ApiResponse<PolicySpecificationResponse>> get(@PathVariable UUID productId) {
        return ResponseEntity.ok(ApiResponse.success(service.get(productId)));
    }

    @PutMapping
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<PolicySpecificationResponse>> upsert(
            @PathVariable UUID productId, @Valid @RequestBody PolicySpecificationRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.upsert(productId, request)));
    }
}
