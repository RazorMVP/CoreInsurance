package com.nubeero.cia.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Projection DTO for GET /api/v1/payments. Includes beneficiaryType +
 *  beneficiaryReference so the flat-list UI can show "Claim CLM-001234"
 *  vs "Commission BRK-007" vs "FAC Outward FAC-2026-009".
 *
 *  beneficiaryType is resolved from CreditNote.entityType (FinanceEntityType enum).
 *  beneficiaryReference is resolved from CreditNote.entityReference (denormalised at creation).
 *  No Policy/Customer chain traversal needed — same pattern as ReceiptListItemResponse. */
public record PaymentListItemResponse(
        UUID id,
        String reference,             // = paymentNumber e.g. PAY-2026-00001
        UUID creditNoteId,
        String creditNoteNumber,
        String beneficiaryType,       // = CreditNote.entityType.name()
        String beneficiaryReference,  // = CreditNote.entityReference
        BigDecimal amount,
        PaymentMethod paymentMethod,
        LocalDate paymentDate,
        TransactionStatus status,
        Instant reversedAt,
        String reversedBy,
        String reversalReason,
        Instant createdAt
) {}
