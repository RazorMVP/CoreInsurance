package com.nubeero.cia.setup.finance;

import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.finance.dto.CurrencyRequest;
import com.nubeero.cia.setup.finance.dto.CurrencyResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/setup/currencies")
@RequiredArgsConstructor
public class CurrencyController {

    private final CurrencyService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ResponseEntity<ApiResponse<Page<CurrencyResponse>>> list(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<CurrencyResponse> page = service.list(pageable);
        ApiMeta meta = ApiMeta.builder()
                .total(page.getTotalElements()).page(page.getNumber()).size(page.getSize()).build();
        return ResponseEntity.ok(ApiResponse.success(page, meta));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ResponseEntity<ApiResponse<CurrencyResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(service.get(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('SETUP_CREATE')")
    public ResponseEntity<ApiResponse<CurrencyResponse>> create(
            @Valid @RequestBody CurrencyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<CurrencyResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody CurrencyRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
