package com.nubeero.cia.customer;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.customer.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/individual")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER_CREATE')")
    public ApiResponse<CustomerResponse> createIndividual(
            @Valid @RequestBody IndividualCustomerRequest request) {
        return ApiResponse.success(service.createIndividual(request));
    }

    @PostMapping("/corporate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER_CREATE')")
    public ApiResponse<CustomerResponse> createCorporate(
            @Valid @RequestBody CorporateCustomerRequest request) {
        return ApiResponse.success(service.createCorporate(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CUSTOMER_UPDATE')")
    public ApiResponse<CustomerResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody CustomerUpdateRequest request) {
        return ApiResponse.success(service.update(id, request));
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
