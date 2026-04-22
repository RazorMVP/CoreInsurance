package com.nubeero.cia.common.event;

import java.math.BigDecimal;
import java.util.UUID;

public record ClaimExpenseApprovedEvent(
        UUID expenseId,
        String expenseReference,
        UUID claimId,
        String claimNumber,
        UUID vendorId,
        String vendorName,
        String expenseType,
        BigDecimal amount,
        String currencyCode
) {}
