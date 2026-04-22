package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.product.dto.PolicyNumberFormatRequest;
import com.nubeero.cia.setup.product.dto.PolicyNumberFormatResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/setup/products/{productId}/policy-number-format")
@RequiredArgsConstructor
public class PolicyNumberFormatController {

    private final PolicyNumberFormatService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ResponseEntity<ApiResponse<PolicyNumberFormatResponse>> get(@PathVariable UUID productId) {
        return ResponseEntity.ok(ApiResponse.success(service.getByProduct(productId)));
    }

    @PutMapping
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<PolicyNumberFormatResponse>> upsert(
            @PathVariable UUID productId, @Valid @RequestBody PolicyNumberFormatRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.upsert(productId, request)));
    }
}
