package com.nubeero.cia.customer;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.customer.dto.CustomerDocumentRequest;
import com.nubeero.cia.customer.dto.CustomerDocumentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers/{customerId}/documents")
@Tag(name = "Customer Documents",
     description = "Per-customer document attachments (KYC certificates, board resolutions, additional ID copies). Distinct from the multipart ID document captured at onboarding — those live on the customer/director records directly.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class CustomerDocumentController {

    private final CustomerDocumentService service;

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER_VIEW')")
    @Operation(summary = "List documents attached to a customer")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Document list",
            content = @Content(schema = @Schema(implementation = CustomerDocumentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CUSTOMER_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
    })
    public ApiResponse<List<CustomerDocumentResponse>> list(@PathVariable UUID customerId) {
        return ApiResponse.success(service.list(customerId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER_UPDATE')")
    @Operation(summary = "Attach a document to the customer",
               description = "Document is uploaded separately via /storage; this endpoint records the metadata + storage reference.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Document attached",
            content = @Content(schema = @Schema(implementation = CustomerDocumentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error or storage reference invalid", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CUSTOMER_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
    })
    public ApiResponse<CustomerDocumentResponse> add(
            @PathVariable UUID customerId,
            @Valid @RequestBody CustomerDocumentRequest request) {
        return ApiResponse.success(service.add(customerId, request));
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('CUSTOMER_UPDATE')")
    @Operation(summary = "Remove a document attachment",
               description = "Soft-deletes the metadata row and removes the object from storage. Audit-logged.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Document removed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CUSTOMER_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Customer or document not found", content = @Content)
    })
    public void delete(@PathVariable UUID customerId, @PathVariable UUID documentId) {
        service.delete(customerId, documentId);
    }
}
