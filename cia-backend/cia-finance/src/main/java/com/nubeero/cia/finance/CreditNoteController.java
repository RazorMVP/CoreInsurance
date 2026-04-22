package com.nubeero.cia.finance;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.dto.CreditNoteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/credit-notes")
@RequiredArgsConstructor
public class CreditNoteController {

    private final CreditNoteService service;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<Page<CreditNoteResponse>> list(
            @RequestParam(required = false) CreditNoteStatus status,
            @RequestParam(required = false) UUID entityId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<CreditNote> page = status != null
                ? service.findByStatus(status, pageable)
                : entityId != null
                        ? service.findByEntity(entityId, pageable)
                        : service.findAll(pageable);
        return ApiResponse.success(page.map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<CreditNoteResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('FINANCE_UPDATE')")
    public ApiResponse<Void> cancel(@PathVariable UUID id) {
        service.cancel(id);
        return ApiResponse.success(null);
    }

    private CreditNoteResponse toResponse(CreditNote cn) {
        BigDecimal outstanding = cn.getTotalAmount().subtract(cn.getPaidAmount());
        return new CreditNoteResponse(
                cn.getId(),
                cn.getCreditNoteNumber(),
                cn.getStatus(),
                cn.getEntityType(),
                cn.getEntityId(),
                cn.getEntityReference(),
                cn.getBeneficiaryId(),
                cn.getBeneficiaryName(),
                cn.getDescription(),
                cn.getAmount(),
                cn.getTaxAmount(),
                cn.getTotalAmount(),
                cn.getPaidAmount(),
                outstanding,
                cn.getCurrencyCode(),
                cn.getDueDate(),
                cn.getCreatedAt() != null ? cn.getCreatedAt().atOffset(ZoneOffset.UTC) : null
        );
    }
}
