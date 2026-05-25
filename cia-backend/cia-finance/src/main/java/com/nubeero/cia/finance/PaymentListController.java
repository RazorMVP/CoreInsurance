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
 * {@code GET /api/v1/payments} — flat paginated list of payments across all
 * credit notes, replacing the nested
 * {@code GET /api/v1/credit-notes/{cnId}/payments} endpoint for the F7
 * Payables tab that needs a single cross-CN view.
 *
 * <p>The existing {@link PaymentController} (nested under
 * {@code /api/v1/credit-notes/{creditNoteId}/payments}) is intentionally left
 * in place — it is the canonical write surface (POST payment, reverse) and
 * the per-CN detail surface. This controller is read-only.
 *
 * <p>All optional filter params default to {@code null} (= no filter).
 * {@link PaymentSpecs} handles null-guards so callers can compose freely.
 *
 * @since Slice α — Task 8, F7 Receipt/Payment visibility
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentListController {

    private final PaymentService paymentService;

    public PaymentListController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Returns a page of payments, optionally filtered by status, date range,
     * payment method, or credit-note id.
     *
     * <p>Results are ordered by {@code created_at DESC} so the most recent
     * payment appears first. Soft-deleted payments are always excluded
     * ({@link PaymentService#findAll} appends the {@code deletedAtIsNull}
     * spec unconditionally).
     *
     * @param status        filter to a single {@link TransactionStatus} value
     * @param createdFrom   inclusive lower bound on {@code created_at}
     * @param createdTo     inclusive upper bound on {@code created_at}
     * @param paymentMethod filter to a single {@link PaymentMethod} value
     * @param creditNoteId  return only payments against this credit note
     * @param page          zero-based page index (default 0)
     * @param size          page size (default 20, capped by caller)
     */
    @GetMapping
    @PreAuthorize("hasAuthority('FINANCE_VIEW')")
    public ResponseEntity<ApiResponse<List<PaymentListItemResponse>>> list(
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) UUID creditNoteId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Specification<Payment> spec = Specification
                .where(PaymentSpecs.statusEquals(status))
                .and(PaymentSpecs.createdBetween(createdFrom, createdTo))
                .and(PaymentSpecs.paymentMethodEquals(paymentMethod))
                .and(PaymentSpecs.creditNoteIdEquals(creditNoteId));

        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<PaymentListItemResponse> result = paymentService.findAll(spec, pageable);

        ApiMeta meta = ApiMeta.builder()
                .total(result.getTotalElements())
                .page(result.getNumber())
                .size(result.getSize())
                .build();

        return ResponseEntity.ok(ApiResponse.success(result.getContent(), meta));
    }
}
