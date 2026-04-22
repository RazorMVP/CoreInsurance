package com.nubeero.cia.finance;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.dto.PaymentResponse;
import com.nubeero.cia.finance.dto.PostPaymentRequest;
import com.nubeero.cia.finance.dto.ReverseRequest;
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
@RequestMapping("/api/v1/credit-notes/{creditNoteId}/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService service;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<Page<PaymentResponse>> list(
            @PathVariable UUID creditNoteId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(
                service.findByCreditNote(creditNoteId, pageable).map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<PaymentResponse> get(
            @PathVariable UUID creditNoteId,
            @PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('FINANCE_CREATE')")
    public ApiResponse<PaymentResponse> post(
            @PathVariable UUID creditNoteId,
            @Valid @RequestBody PostPaymentRequest req) {
        Payment payment = service.post(
                creditNoteId, req.amount(), req.paymentDate(), req.paymentMethod(),
                req.bankId(), req.bankName(), req.bankAccountName(),
                req.bankAccountNumber(), req.narration());
        return ApiResponse.success(toResponse(payment));
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasRole('FINANCE_UPDATE')")
    public ApiResponse<Void> reverse(
            @PathVariable UUID creditNoteId,
            @PathVariable UUID id,
            @Valid @RequestBody ReverseRequest req) {
        service.reverse(id, req.reason());
        return ApiResponse.success(null);
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(
                p.getId(),
                p.getPaymentNumber(),
                p.getCreditNote().getId(),
                p.getCreditNote().getCreditNoteNumber(),
                p.getAmount(),
                p.getPaymentDate(),
                p.getPaymentMethod(),
                p.getBankId(),
                p.getBankName(),
                p.getBankAccountName(),
                p.getBankAccountNumber(),
                p.getNarration(),
                p.getPostedBy(),
                p.getStatus(),
                p.getReversalReason(),
                p.getReversedAt(),
                p.getReversedBy(),
                p.getCreatedAt()
        );
    }
}
