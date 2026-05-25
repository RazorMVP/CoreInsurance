package com.nubeero.cia.finance;

import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/receipts} — flat paginated list of receipts across all
 * debit notes, replacing the nested
 * {@code GET /api/v1/debit-notes/{dnId}/receipts} endpoint for the F7
 * Receivables tab that needs a single cross-DN view.
 *
 * <p>The existing {@link ReceiptController} (nested under
 * {@code /api/v1/debit-notes/{debitNoteId}/receipts}) is intentionally left
 * in place — it is the canonical write surface (POST receipt, reverse) and
 * the per-DN detail surface. This controller is read-only.
 *
 * <p>All optional filter params default to {@code null} (= no filter).
 * {@link ReceiptSpecs} handles null-guards so callers can compose freely.
 *
 * @since Slice α — Task 7, F7 Receipt/Payment visibility
 */
@RestController
@RequestMapping("/api/v1/receipts")
public class ReceiptListController {

    private final ReceiptService receiptService;

    public ReceiptListController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    /**
     * Returns a page of receipts, optionally filtered by status, date range,
     * payment method, or debit-note id.
     *
     * <p>Results are ordered by {@code created_at DESC} so the most recent
     * receipt appears first. Soft-deleted receipts are always excluded
     * ({@link ReceiptService#findAll} appends the {@code deletedAtIsNull}
     * spec unconditionally).
     *
     * @param status        filter to a single {@link TransactionStatus} value
     * @param createdFrom   inclusive lower bound on {@code created_at}
     * @param createdTo     inclusive upper bound on {@code created_at}
     * @param paymentMethod filter to a single {@link PaymentMethod} value
     * @param debitNoteId   return only receipts against this debit note
     * @param page          zero-based page index (default 0)
     * @param size          page size (default 20, capped by caller)
     */
    @GetMapping
    @PreAuthorize("hasAuthority('FINANCE_VIEW')")
    public ResponseEntity<ApiResponse<List<ReceiptListItemResponse>>> list(
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) UUID debitNoteId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Specification<Receipt> spec = Specification
                .where(ReceiptSpecs.statusEquals(status))
                .and(ReceiptSpecs.createdBetween(createdFrom, createdTo))
                .and(ReceiptSpecs.paymentMethodEquals(paymentMethod))
                .and(ReceiptSpecs.debitNoteIdEquals(debitNoteId));

        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<ReceiptListItemResponse> result = receiptService.findAll(spec, pageable);

        ApiMeta meta = ApiMeta.builder()
                .total(result.getTotalElements())
                .page(result.getNumber())
                .size(result.getSize())
                .build();

        return ResponseEntity.ok(ApiResponse.success(result.getContent(), meta));
    }
}
