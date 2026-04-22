package com.nubeero.cia.finance;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.dto.PostReceiptRequest;
import com.nubeero.cia.finance.dto.ReceiptResponse;
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
@RequestMapping("/api/v1/debit-notes/{debitNoteId}/receipts")
@RequiredArgsConstructor
public class ReceiptController {

    private final ReceiptService service;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<Page<ReceiptResponse>> list(
            @PathVariable UUID debitNoteId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(
                service.findByDebitNote(debitNoteId, pageable).map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<ReceiptResponse> get(
            @PathVariable UUID debitNoteId,
            @PathVariable UUID id) {
        Receipt receipt = service.findOrThrow(id);
        return ApiResponse.success(toResponse(receipt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('FINANCE_CREATE')")
    public ApiResponse<ReceiptResponse> post(
            @PathVariable UUID debitNoteId,
            @Valid @RequestBody PostReceiptRequest req) {
        Receipt receipt = service.post(
                debitNoteId, req.amount(), req.paymentDate(), req.paymentMethod(),
                req.bankId(), req.bankName(), req.chequeNumber(), req.narration());
        return ApiResponse.success(toResponse(receipt));
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasRole('FINANCE_UPDATE')")
    public ApiResponse<Void> reverse(
            @PathVariable UUID debitNoteId,
            @PathVariable UUID id,
            @Valid @RequestBody ReverseRequest req) {
        service.reverse(id, req.reason());
        return ApiResponse.success(null);
    }

    private ReceiptResponse toResponse(Receipt r) {
        return new ReceiptResponse(
                r.getId(),
                r.getReceiptNumber(),
                r.getDebitNote().getId(),
                r.getDebitNote().getDebitNoteNumber(),
                r.getAmount(),
                r.getPaymentDate(),
                r.getPaymentMethod(),
                r.getBankId(),
                r.getBankName(),
                r.getChequeNumber(),
                r.getNarration(),
                r.getPostedBy(),
                r.getStatus(),
                r.getReversalReason(),
                r.getReversedAt(),
                r.getReversedBy(),
                r.getCreatedAt()
        );
    }
}
