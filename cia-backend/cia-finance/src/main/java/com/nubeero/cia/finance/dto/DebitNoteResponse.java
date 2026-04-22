package com.nubeero.cia.finance.dto;

import com.nubeero.cia.finance.DebitNoteStatus;
import com.nubeero.cia.finance.FinanceEntityType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DebitNoteResponse(
        UUID id,
        String debitNoteNumber,
        DebitNoteStatus status,
        FinanceEntityType entityType,
        UUID entityId,
        String entityReference,
        UUID customerId,
        String customerName,
        UUID brokerId,
        String brokerName,
        String productName,
        String description,
        BigDecimal amount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        BigDecimal outstandingAmount,
        String currencyCode,
        LocalDate dueDate,
        Instant createdAt
) {}
