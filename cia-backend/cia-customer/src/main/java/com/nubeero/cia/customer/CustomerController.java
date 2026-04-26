package com.nubeero.cia.customer;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.customer.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
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
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService service;

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER_VIEW')")
    public ApiResponse<Page<CustomerSummaryResponse>> list(
            @RequestParam(required = false) CustomerType type,
            @RequestParam(required = false) KycStatus kycStatus,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.list(type, kycStatus, pageable));
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('CUSTOMER_VIEW')")
    public ApiResponse<Page<CustomerSummaryResponse>> search(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.search(q, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CUSTOMER_VIEW')")
    public ApiResponse<CustomerResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping(value = "/individual", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER_CREATE')")
    public ApiResponse<CustomerResponse> createIndividual(
            @Valid @ModelAttribute IndividualCustomerRequest request,
            @RequestPart("idDocument") MultipartFile idDocument) {
        return ApiResponse.success(service.createIndividual(request, idDocument));
    }

    @PostMapping(value = "/corporate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER_CREATE')")
    public ApiResponse<CustomerResponse> createCorporate(
            @Valid @ModelAttribute CorporateCustomerRequest request,
            @RequestPart("cacCertificate") MultipartFile cacCertificate,
            @RequestPart(value = "directorIdDocuments", required = false) List<MultipartFile> directorIdDocuments) {
        return ApiResponse.success(service.createCorporate(request, cacCertificate, directorIdDocuments));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('CUSTOMER_UPDATE')")
    public ApiResponse<CustomerResponse> update(
            @PathVariable UUID id,
            @Valid @ModelAttribute CustomerUpdateRequest request,
            MultipartRequest multipartRequest) {

        MultipartFile idDocument = multipartRequest.getFile("idDocument");

        // Collect per-director documents keyed as directorDoc_{index}
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
    public ApiResponse<CustomerResponse> retriggerKyc(@PathVariable UUID id) {
        return ApiResponse.success(service.retriggerKyc(id));
    }

    @PostMapping("/{id}/blacklist")
    @PreAuthorize("hasRole('CUSTOMER_UPDATE')")
    public ApiResponse<CustomerResponse> blacklist(
            @PathVariable UUID id,
            @Valid @RequestBody BlacklistRequest request) {
        return ApiResponse.success(service.blacklist(id, request));
    }

    @DeleteMapping("/{id}/blacklist")
    @PreAuthorize("hasRole('CUSTOMER_UPDATE')")
    public ApiResponse<CustomerResponse> unblacklist(@PathVariable UUID id) {
        return ApiResponse.success(service.unblacklist(id));
    }
}
