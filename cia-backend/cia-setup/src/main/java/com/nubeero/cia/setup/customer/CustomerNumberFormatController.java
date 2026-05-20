package com.nubeero.cia.setup.customer;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.customer.dto.CustomerNumberFormatRequest;
import com.nubeero.cia.setup.customer.dto.CustomerNumberFormatResponse;
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

@RestController
@RequestMapping("/api/v1/setup/customer-number-format")
@Tag(name = "Setup — Customer Number Format", description = "Per-tenant singleton config — controls customer number generation (prefix, year, type-segment, sequence length). Format: {prefix}/{year}/{type}/{sequence} (e.g. CUST/2026/IND/00000001). Lookup via PESSIMISTIC_WRITE in CustomerNumberFormatService — prevents duplicate numbers under concurrent onboarding.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class CustomerNumberFormatController {

    private final CustomerNumberFormatService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    @Operation(summary = "Get the customer number format (tenant singleton)",
               description = "Returns 404 if no format is configured — customer onboarding will fail with CUSTOMER_NUMBER_FORMAT_NOT_CONFIGURED until this is set.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Customer number format",
            content = @Content(schema = @Schema(implementation = CustomerNumberFormatResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not yet configured", content = @Content)
    })
    public ResponseEntity<ApiResponse<CustomerNumberFormatResponse>> get() {
        return ResponseEntity.ok(ApiResponse.success(service.get()));
    }

    @PutMapping
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Upsert the customer number format")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Upserted",
            content = @Content(schema = @Schema(implementation = CustomerNumberFormatResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<CustomerNumberFormatResponse>> upsert(
            @Valid @RequestBody CustomerNumberFormatRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.upsert(request)));
    }
}
