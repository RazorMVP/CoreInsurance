package com.nubeero.cia.claims.dto;

import com.nubeero.cia.claims.ClaimExpenseStatus;
import com.nubeero.cia.claims.ClaimExpenseType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ClaimExpenseResponse(
        UUID id,
        UUID claimId,
        ClaimExpenseType expenseType,
        ClaimExpenseStatus status,
        UUID vendorId,
        String vendorName,
        BigDecimal amount,
        String description,
        String approvedBy,
        Instant approvedAt,
        String cancelledBy,
        Instant cancelledAt,
        String cancellationReason,
        Instant createdAt
) {}
