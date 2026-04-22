package com.nubeero.cia.finance.dto;

import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ReceiptResponse(
        UUID id,
        String receiptNumber,
        UUID debitNoteId,
        String debitNoteNumber,
        BigDecimal amount,
        LocalDate paymentDate,
        PaymentMethod paymentMethod,
        UUID bankId,
        String bankName,
        String chequeNumber,
        String narration,
        String postedBy,
        TransactionStatus status,
        String reversalReason,
        Instant reversedAt,
        String reversedBy,
        Instant createdAt
) {}
