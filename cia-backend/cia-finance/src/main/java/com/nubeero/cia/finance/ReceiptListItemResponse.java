package com.nubeero.cia.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Projection DTO for the flat list endpoint GET /api/v1/receipts.
 *  Carries DN + policy + customer context so the table row does not need to
 *  separately fetch each parent. Reversal columns are populated when
 *  status == REVERSED. */
public record ReceiptListItemResponse(
        UUID id,
        String reference,            // = receiptNumber e.g. REC-2026-00001
        UUID debitNoteId,
        String debitNoteNumber,
        String policyNumber,         // nullable — DN may not be policy-backed
        String customerName,         // nullable — denormalised from DN.customerName
        BigDecimal amount,
        PaymentMethod paymentMethod,
        LocalDate paymentDate,
        TransactionStatus status,
        Instant reversedAt,          // nullable
        String reversedBy,           // nullable
        String reversalReason,       // nullable
        Instant createdAt,
        String pdfPath               // nullable — null = PDF was never generated
) {}
