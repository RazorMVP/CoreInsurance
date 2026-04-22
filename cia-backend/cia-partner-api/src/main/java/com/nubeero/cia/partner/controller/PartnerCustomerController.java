package com.nubeero.cia.partner.controller;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.customer.CustomerService;
import com.nubeero.cia.customer.dto.CorporateCustomerRequest;
import com.nubeero.cia.customer.dto.IndividualCustomerRequest;
import com.nubeero.cia.partner.controller.dto.PartnerCustomerResponse;
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
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/partner/v1/customers")
@Tag(name = "Customers", description = "Customer registration and KYC status")
@SecurityRequirement(name = "bearer-key")
@RequiredArgsConstructor
public class PartnerCustomerController {

    private final CustomerService customerService;

    @PostMapping("/individual")
    @Operation(summary = "Register an individual customer",
               description = "Creates a customer record and triggers asynchronous KYC verification. " +
                             "Poll GET /customers/{id} to check kycStatus until it is VERIFIED or FAILED.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Customer registered — KYC verification initiated",
            content = @Content(schema = @Schema(implementation = PartnerCustomerResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error — missing or invalid fields", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<ApiResponse<PartnerCustomerResponse>> createIndividual(
            @Valid @RequestBody IndividualCustomerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        PartnerCustomerResponse.from(customerService.createIndividual(request))));
    }

    @PostMapping("/corporate")
    @Operation(summary = "Register a corporate customer",
               description = "Creates a corporate customer and triggers asynchronous KYC verification against the CAC RC Number. " +
                             "At least two director IDs are required for KYC.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Corporate customer registered — KYC verification initiated",
            content = @Content(schema = @Schema(implementation = PartnerCustomerResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error — missing or invalid fields", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<ApiResponse<PartnerCustomerResponse>> createCorporate(
            @Valid @RequestBody CorporateCustomerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        PartnerCustomerResponse.from(customerService.createCorporate(request))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get customer details and KYC status",
               description = "Returns customer profile and current KYC status. Use to poll after registration.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Customer found",
            content = @Content(schema = @Schema(implementation = PartnerCustomerResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Customer not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<ApiResponse<PartnerCustomerResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(
                PartnerCustomerResponse.from(customerService.get(id))));
    }
}
