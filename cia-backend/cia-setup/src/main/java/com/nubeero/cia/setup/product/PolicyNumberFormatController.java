package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.product.dto.PolicyNumberFormatRequest;
import com.nubeero.cia.setup.product.dto.PolicyNumberFormatResponse;
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
@RequestMapping("/api/v1/setup/products/{productId}/policy-number-format")
@Tag(name = "Setup — Policy Number Format", description = "Per-product singleton config — controls how policy numbers are generated (prefix, year placement, sequence length, etc).")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class PolicyNumberFormatController {

    private final PolicyNumberFormatService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    @Operation(summary = "Get the policy number format for a product")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Format (default if not yet configured)",
            content = @Content(schema = @Schema(implementation = PolicyNumberFormatResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Product not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<PolicyNumberFormatResponse>> get(@PathVariable UUID productId) {
        return ResponseEntity.ok(ApiResponse.success(service.getByProduct(productId)));
    }

    @PutMapping
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Upsert the policy number format (singleton-per-product)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Upserted",
            content = @Content(schema = @Schema(implementation = PolicyNumberFormatResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Product not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<PolicyNumberFormatResponse>> upsert(
            @PathVariable UUID productId, @Valid @RequestBody PolicyNumberFormatRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.upsert(productId, request)));
    }
}
