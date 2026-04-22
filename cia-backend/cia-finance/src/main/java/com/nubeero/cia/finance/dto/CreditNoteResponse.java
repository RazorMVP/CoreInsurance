package com.nubeero.cia.finance.dto;

import com.nubeero.cia.finance.CreditNoteStatus;
import com.nubeero.cia.finance.FinanceEntityType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CreditNoteResponse(
        UUID id,
        String creditNoteNumber,
        CreditNoteStatus status,
        FinanceEntityType entityType,
        UUID entityId,
        String entityReference,
        UUID beneficiaryId,
        String beneficiaryName,
        String description,
        BigDecimal amount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        BigDecimal outstandingAmount,
        String currencyCode,
        LocalDate dueDate,
        OffsetDateTime createdAt
) {}
