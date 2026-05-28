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
        String pdfPath,              // nullable — null = PDF was never generated
        // Slice γ — email transmission. recipientEmail is the pre-resolved
        // customer email at projection time (drives the Email button enabled
        // state on the frontend). emailSentAt / emailSentTo are populated by
        // the Temporal email-workflow activity after a successful delivery.
        String recipientEmail,       // nullable — gates the Email button
        Instant emailSentAt,         // nullable — null until first successful send
        String emailSentTo,          // nullable — = recipientEmail at send time
        // Task 6.2 — SMS transmission. recipientPhone is the pre-resolved
        // customer phone at projection time (drives the SMS button enabled
        // state on the frontend). smsSentAt / smsSentTo are populated by
        // the Temporal sms-workflow activity after a successful delivery.
        String recipientPhone,       // nullable — gates the SMS button
        Instant smsSentAt,           // nullable — null until first successful send
        String smsSentTo             // nullable — = recipientPhone at send time
) {}
