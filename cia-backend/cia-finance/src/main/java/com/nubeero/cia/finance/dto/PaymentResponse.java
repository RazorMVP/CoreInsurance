package com.nubeero.cia.finance.dto;

import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        String paymentNumber,
        UUID creditNoteId,
        String creditNoteNumber,
        BigDecimal amount,
        LocalDate paymentDate,
        PaymentMethod paymentMethod,
        UUID bankId,
        String bankName,
        String bankAccountName,
        String bankAccountNumber,
        String narration,
        String postedBy,
        TransactionStatus status,
        String reversalReason,
        Instant reversedAt,
        String reversedBy,
        Instant createdAt
) {}
