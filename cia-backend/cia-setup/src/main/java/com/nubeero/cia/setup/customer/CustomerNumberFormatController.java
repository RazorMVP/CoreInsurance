package com.nubeero.cia.setup.customer;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.customer.dto.CustomerNumberFormatRequest;
import com.nubeero.cia.setup.customer.dto.CustomerNumberFormatResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/setup/customer-number-format")
@RequiredArgsConstructor
public class CustomerNumberFormatController {

    private final CustomerNumberFormatService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ResponseEntity<ApiResponse<CustomerNumberFormatResponse>> get() {
        return ResponseEntity.ok(ApiResponse.success(service.get()));
    }

    @PutMapping
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<CustomerNumberFormatResponse>> upsert(
            @Valid @RequestBody CustomerNumberFormatRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.upsert(request)));
    }
}
