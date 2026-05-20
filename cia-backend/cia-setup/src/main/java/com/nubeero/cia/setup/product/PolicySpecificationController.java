package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.product.dto.PolicySpecificationRequest;
import com.nubeero.cia.setup.product.dto.PolicySpecificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/setup/products/{productId}/policy-specification")
@Tag(name = "Setup — Policy Specification", description = "Per-product singleton config — controls the policy document template (clause bank, layout, signatures).")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class PolicySpecificationController {

    private final PolicySpecificationService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    @Operation(summary = "Get the policy specification for a product")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Specification (default if not configured)",
            content = @Content(schema = @Schema(implementation = PolicySpecificationResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Product not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<PolicySpecificationResponse>> get(@PathVariable UUID productId) {
        return ResponseEntity.ok(ApiResponse.success(service.get(productId)));
    }

    @PutMapping
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Upsert the policy specification (singleton-per-product)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Upserted",
            content = @Content(schema = @Schema(implementation = PolicySpecificationResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Product not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<PolicySpecificationResponse>> upsert(
            @PathVariable UUID productId, @Valid @RequestBody PolicySpecificationRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.upsert(productId, request)));
    }
}
