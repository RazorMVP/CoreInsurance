package com.nubeero.cia.customer;

import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.customer.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartRequest;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers (Module 7)",
     description = "Customer onboarding (individual + corporate) with embedded KYC. Customer number format is per-tenant (CUST/2026/IND/00000001 by default); collision-safe via PESSIMISTIC_WRITE lock in CustomerNumberFormatService. Multipart uploads carry ID document + (for corporate) CAC certificate + director IDs. KYC re-triggers automatically on relevant field updates.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService service;

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER_VIEW')")
    @Operation(summary = "List customers (paginated, filterable)",
               description = "Filter by customer type (INDIVIDUAL / CORPORATE) and/or KYC status (PENDING / VERIFIED / FAILED). Returns lightweight summary projection.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Customer page",
            content = @Content(schema = @Schema(implementation = CustomerSummaryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CUSTOMER_VIEW", content = @Content)
    })
    public ApiResponse<List<CustomerSummaryResponse>> list(
            @RequestParam(required = false) CustomerType type,
            @RequestParam(required = false) KycStatus kycStatus,
            @PageableDefault(size = 2000) Pageable pageable) {
        var page = service.list(type, kycStatus, pageable);
        return ApiResponse.success(page.getContent(),
                ApiMeta.builder()
                        .total(page.getTotalElements())
                        .page(page.getNumber())
                        .size(page.getSize())
                        .build());
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('CUSTOMER_VIEW')")
    @Operation(summary = "Search customers by free text",
               description = "Matches against customer number, first/last name (individual), company name (corporate), email, phone. Encrypted PII fields (id_number, address) are NOT searched — they live behind pgcrypto.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Matching customers",
            content = @Content(schema = @Schema(implementation = CustomerSummaryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CUSTOMER_VIEW", content = @Content)
    })
    public ApiResponse<List<CustomerSummaryResponse>> search(
            @RequestParam String q,
            @PageableDefault(size = 2000) Pageable pageable) {
        var page = service.search(q, pageable);
        return ApiResponse.success(page.getContent(),
                ApiMeta.builder()
                        .total(page.getTotalElements())
                        .page(page.getNumber())
                        .size(page.getSize())
                        .build());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CUSTOMER_VIEW')")
    @Operation(summary = "Get customer detail",
               description = "Returns the full customer including KYC status, decrypted PII (id_number, address), directors (for corporate), and channel (Direct or broker).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Customer found",
            content = @Content(schema = @Schema(implementation = CustomerResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CUSTOMER_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
    })
    public ApiResponse<CustomerResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping(value = "/individual", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER_CREATE')")
    @Operation(summary = "Onboard an individual customer",
               description = "Multipart request. Generates a customer number via CustomerNumberFormatService (requires per-tenant format configured — 400 CUSTOMER_NUMBER_FORMAT_NOT_CONFIGURED otherwise). Calls KycVerificationService synchronously for individual KYC (NIN / Voter's Card / DL / Passport).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Customer created (KYC status may be VERIFIED or FAILED)",
            content = @Content(schema = @Schema(implementation = CustomerResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error or customer number format not configured", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CUSTOMER_CREATE", content = @Content)
    })
    public ApiResponse<CustomerResponse> createIndividual(
            @Valid @ModelAttribute IndividualCustomerRequest request,
            @RequestPart("idDocument") MultipartFile idDocument) {
        return ApiResponse.success(service.createIndividual(request, idDocument));
    }

    @PostMapping(value = "/corporate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER_CREATE')")
    @Operation(summary = "Onboard a corporate customer",
               description = "Multipart request — RC number + CAC certificate + at least 2 directors (enforced by MINIMUM_DIRECTORS_REQUIRED rule). KYC validates company name against RC number; each director's KYC runs against their ID.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Corporate customer created",
            content = @Content(schema = @Schema(implementation = CustomerResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error, fewer than 2 directors, or RC number conflict", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CUSTOMER_CREATE", content = @Content)
    })
    public ApiResponse<CustomerResponse> createCorporate(
            @Valid @ModelAttribute CorporateCustomerRequest request,
            @RequestPart("cacCertificate") MultipartFile cacCertificate,
            @RequestPart(value = "directorIdDocuments", required = false) List<MultipartFile> directorIdDocuments) {
        return ApiResponse.success(service.createCorporate(request, cacCertificate, directorIdDocuments));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('CUSTOMER_UPDATE')")
    @Operation(summary = "Update customer (KYC re-trigger if KYC fields change)",
               description = "Multipart with optional idDocument + per-director documents keyed as directorDoc_{index}. Updating any KYC field requires a reason (audit-logged twice: general UPDATE + dedicated CustomerKyc UPDATE with the reason). Corporate customers must keep at least 2 active directors at all times.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Customer updated",
            content = @Content(schema = @Schema(implementation = CustomerResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error, KYC reason missing, or would drop director count below 2", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CUSTOMER_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
    })
    public ApiResponse<CustomerResponse> update(
            @PathVariable UUID id,
            @Valid @ModelAttribute CustomerUpdateRequest request,
            MultipartRequest multipartRequest) {

        MultipartFile idDocument = multipartRequest.getFile("idDocument");

        java.util.Map<String, MultipartFile> directorDocs = new java.util.HashMap<>();
        java.util.Iterator<String> fileNames = multipartRequest.getFileNames();
        while (fileNames.hasNext()) {
            String name = fileNames.next();
            if (name.startsWith("directorDoc_")) {
                directorDocs.put(name, multipartRequest.getFile(name));
            }
        }

        return ApiResponse.success(service.update(id, request, idDocument, directorDocs));
    }

    @PostMapping("/{id}/retrigger-kyc")
    @PreAuthorize("hasRole('CUSTOMER_UPDATE')")
    @Operation(summary = "Re-trigger KYC verification on-demand",
               description = "Manually re-runs KYC against the current ID details. Used when KYC provider returned a transient failure, or after the provider's data refresh window. Updates kyc_status accordingly and audit-logs the result.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "KYC re-triggered",
            content = @Content(schema = @Schema(implementation = CustomerResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CUSTOMER_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
    })
    public ApiResponse<CustomerResponse> retriggerKyc(@PathVariable UUID id) {
        return ApiResponse.success(service.retriggerKyc(id));
    }

    @PostMapping("/{id}/blacklist")
    @PreAuthorize("hasRole('CUSTOMER_UPDATE')")
    @Operation(summary = "Blacklist a customer",
               description = "Flags the customer as BLACKLISTED with a mandatory reason. Existing policies remain active but quote/policy creation against this customer is rejected. Audit-logged.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Customer blacklisted",
            content = @Content(schema = @Schema(implementation = CustomerResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CUSTOMER_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
    })
    public ApiResponse<CustomerResponse> blacklist(
            @PathVariable UUID id,
            @Valid @RequestBody BlacklistRequest request) {
        return ApiResponse.success(service.blacklist(id, request));
    }

    @DeleteMapping("/{id}/blacklist")
    @PreAuthorize("hasRole('CUSTOMER_UPDATE')")
    @Operation(summary = "Remove customer from blacklist",
               description = "Restores the customer to normal status. Audit-logged; the blacklist history survives for audit even after removal.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Customer unblacklisted",
            content = @Content(schema = @Schema(implementation = CustomerResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CUSTOMER_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Customer not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Customer not currently blacklisted", content = @Content)
    })
    public ApiResponse<CustomerResponse> unblacklist(@PathVariable UUID id) {
        return ApiResponse.success(service.unblacklist(id));
    }
}
