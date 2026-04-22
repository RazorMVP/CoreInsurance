package com.nubeero.cia.customer;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.customer.dto.CustomerDocumentRequest;
import com.nubeero.cia.customer.dto.CustomerDocumentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers/{customerId}/documents")
@RequiredArgsConstructor
public class CustomerDocumentController {

    private final CustomerDocumentService service;

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER_VIEW')")
    public ApiResponse<List<CustomerDocumentResponse>> list(@PathVariable UUID customerId) {
        return ApiResponse.success(service.list(customerId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER_UPDATE')")
    public ApiResponse<CustomerDocumentResponse> add(
            @PathVariable UUID customerId,
            @Valid @RequestBody CustomerDocumentRequest request) {
        return ApiResponse.success(service.add(customerId, request));
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('CUSTOMER_UPDATE')")
    public void delete(@PathVariable UUID customerId, @PathVariable UUID documentId) {
        service.delete(customerId, documentId);
    }
}
