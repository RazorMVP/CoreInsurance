package com.nubeero.cia.setup.quote;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.quote.dto.AdjustmentTypeRequest;
import com.nubeero.cia.setup.quote.dto.AdjustmentTypeResponse;
import com.nubeero.cia.setup.quote.dto.QuoteConfigRequest;
import com.nubeero.cia.setup.quote.dto.QuoteConfigResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Setup — Quote Config",
     description = "Per-tenant quote-engine configuration: singleton config (validity period, premium calculation sequence LOADING_FIRST vs DISCOUNT_FIRST) + CRUD on discount-type and loading-type master data. Drives Module 2 quote premium calculation.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class QuoteConfigController {

    private final QuoteConfigService service;

    // ── Quote Config ──────────────────────────────────────────────────────────

    @GetMapping("/api/v1/setup/quote-config")
    @PreAuthorize("hasRole('SETUP_VIEW')")
    @Operation(summary = "Get the quote config (tenant singleton)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Quote config",
            content = @Content(schema = @Schema(implementation = QuoteConfigResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<QuoteConfigResponse>> getConfig() {
        return ResponseEntity.ok(ApiResponse.success(service.getConfig()));
    }

    @PutMapping("/api/v1/setup/quote-config")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Update the quote config",
               description = "Changes the premium calculation sequence (LOADING_FIRST | DISCOUNT_FIRST) and validity period. Affects ALL subsequent quote creations — existing quotes lock in the sequence at create time.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated",
            content = @Content(schema = @Schema(implementation = QuoteConfigResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<QuoteConfigResponse>> updateConfig(
            @Valid @RequestBody QuoteConfigRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.updateConfig(request)));
    }

    // ── Discount types ────────────────────────────────────────────────────────

    @GetMapping("/api/v1/setup/quote-discount-types")
    @PreAuthorize("hasRole('SETUP_VIEW')")
    @Operation(summary = "List discount types")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Discount type list",
            content = @Content(schema = @Schema(implementation = AdjustmentTypeResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<List<AdjustmentTypeResponse>>> listDiscountTypes() {
        return ResponseEntity.ok(ApiResponse.success(service.listDiscountTypes()));
    }

    @PostMapping("/api/v1/setup/quote-discount-types")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Create a discount type")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Created",
            content = @Content(schema = @Schema(implementation = AdjustmentTypeResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<AdjustmentTypeResponse>> createDiscountType(
            @Valid @RequestBody AdjustmentTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.createDiscountType(request)));
    }

    @PutMapping("/api/v1/setup/quote-discount-types/{id}")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Update a discount type")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated",
            content = @Content(schema = @Schema(implementation = AdjustmentTypeResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<AdjustmentTypeResponse>> updateDiscountType(
            @PathVariable UUID id,
            @Valid @RequestBody AdjustmentTypeRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.updateDiscountType(id, request)));
    }

    @DeleteMapping("/api/v1/setup/quote-discount-types/{id}")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Soft-delete discount type")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<Void>> deleteDiscountType(@PathVariable UUID id) {
        service.deleteDiscountType(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── Loading types ─────────────────────────────────────────────────────────

    @GetMapping("/api/v1/setup/quote-loading-types")
    @PreAuthorize("hasRole('SETUP_VIEW')")
    @Operation(summary = "List loading types")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Loading type list",
            content = @Content(schema = @Schema(implementation = AdjustmentTypeResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<List<AdjustmentTypeResponse>>> listLoadingTypes() {
        return ResponseEntity.ok(ApiResponse.success(service.listLoadingTypes()));
    }

    @PostMapping("/api/v1/setup/quote-loading-types")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Create a loading type")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Created",
            content = @Content(schema = @Schema(implementation = AdjustmentTypeResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<AdjustmentTypeResponse>> createLoadingType(
            @Valid @RequestBody AdjustmentTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.createLoadingType(request)));
    }

    @PutMapping("/api/v1/setup/quote-loading-types/{id}")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Update a loading type")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated",
            content = @Content(schema = @Schema(implementation = AdjustmentTypeResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<AdjustmentTypeResponse>> updateLoadingType(
            @PathVariable UUID id,
            @Valid @RequestBody AdjustmentTypeRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.updateLoadingType(id, request)));
    }

    @DeleteMapping("/api/v1/setup/quote-loading-types/{id}")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Soft-delete loading type")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<Void>> deleteLoadingType(@PathVariable UUID id) {
        service.deleteLoadingType(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
