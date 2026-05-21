package com.nubeero.cia.finance.ifrs9;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Wire representation of one row in the Type-2 SCD
 * {@code investment_classification_history} table. Surfaced by
 * {@code GET /api/v1/finance/ifrs9/holdings/{holdingId}/classification-history}.
 *
 * <p>NAICOM auditors require the full reclassification trail per
 * §B4.1.26. The DTO carries the previous + new classification, the
 * reclassification date, the textual reason, and the approver — the four
 * fields auditors sample at year-end.
 *
 * @since Module 12 Phase 5 frontend slice F5.12
 */
public record InvestmentClassificationHistoryResponse(
    UUID id,
    UUID holdingId,
    InvestmentClassification previousClassification,
    InvestmentClassification newClassification,
    LocalDate reclassificationDate,
    String reason,
    String approvedBy,
    Instant createdAt
) {
    public static InvestmentClassificationHistoryResponse from(InvestmentClassificationHistory h) {
        return new InvestmentClassificationHistoryResponse(
            h.getId(),
            h.getHolding().getId(),
            h.getPreviousClassification(),
            h.getNewClassification(),
            h.getReclassificationDate(),
            h.getReason(),
            h.getApprovedBy(),
            h.getCreatedAt()
        );
    }
}
