package com.nubeero.cia.quotation;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.quotation.dto.*;
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
@RequestMapping("/api/v1/quotes")
@RequiredArgsConstructor
public class QuoteController {

    private final QuoteService service;

    @GetMapping
    @PreAuthorize("hasRole('QUOTATION_VIEW')")
    public ApiResponse<Page<QuoteSummaryResponse>> list(
            @RequestParam(required = false) QuoteStatus status,
            @RequestParam(required = false) UUID customerId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.list(status, customerId, pageable));
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('QUOTATION_VIEW')")
    public ApiResponse<Page<QuoteSummaryResponse>> search(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.search(q, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('QUOTATION_VIEW')")
    public ApiResponse<QuoteResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('QUOTATION_CREATE')")
    public ApiResponse<QuoteResponse> create(@Valid @RequestBody QuoteRequest request) {
        return ApiResponse.success(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('QUOTATION_UPDATE')")
    public ApiResponse<QuoteResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody QuoteUpdateRequest request) {
        return ApiResponse.success(service.update(id, request));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('QUOTATION_UPDATE')")
    public ApiResponse<QuoteResponse> submit(@PathVariable UUID id) {
        return ApiResponse.success(service.submit(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('QUOTATION_APPROVE')")
    public ApiResponse<QuoteResponse> approve(
            @PathVariable UUID id,
            @RequestBody(required = false) QuoteApprovalRequest request) {
        return ApiResponse.success(service.approve(id, request));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('QUOTATION_APPROVE')")
    public ApiResponse<QuoteResponse> reject(
            @PathVariable UUID id,
            @RequestBody(required = false) QuoteApprovalRequest request) {
        return ApiResponse.success(service.reject(id, request));
    }
}
