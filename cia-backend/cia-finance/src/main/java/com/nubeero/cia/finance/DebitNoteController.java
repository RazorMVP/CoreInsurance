package com.nubeero.cia.finance;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.dto.DebitNoteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/debit-notes")
@RequiredArgsConstructor
public class DebitNoteController {

    private final DebitNoteService service;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<Page<DebitNoteResponse>> list(
            @RequestParam(required = false) DebitNoteStatus status,
            @RequestParam(required = false) UUID customerId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<DebitNote> page = status != null
                ? service.findByStatus(status, pageable)
                : customerId != null
                        ? service.findByCustomer(customerId, pageable)
                        : service.findAll(pageable);
        return ApiResponse.success(page.map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<DebitNoteResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('FINANCE_UPDATE')")
    public ApiResponse<Void> cancel(@PathVariable UUID id) {
        service.cancel(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasRole('FINANCE_UPDATE')")
    public ApiResponse<Void> markVoid(@PathVariable UUID id) {
        service.markVoid(id);
        return ApiResponse.success(null);
    }

    private DebitNoteResponse toResponse(DebitNote dn) {
        BigDecimal outstanding = dn.getTotalAmount().subtract(dn.getPaidAmount());
        return new DebitNoteResponse(
                dn.getId(),
                dn.getDebitNoteNumber(),
                dn.getStatus(),
                dn.getEntityType(),
                dn.getEntityId(),
                dn.getEntityReference(),
                dn.getCustomerId(),
                dn.getCustomerName(),
                dn.getBrokerId(),
                dn.getBrokerName(),
                dn.getProductName(),
                dn.getDescription(),
                dn.getAmount(),
                dn.getTaxAmount(),
                dn.getTotalAmount(),
                dn.getPaidAmount(),
                outstanding,
                dn.getCurrencyCode(),
                dn.getDueDate(),
                dn.getCreatedAt()
        );
    }
}
