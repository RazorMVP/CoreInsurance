package com.nubeero.cia.documents;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PolicyDocumentContext(
        UUID policyId,
        String policyNumber,
        UUID productId,
        String productName,
        UUID classOfBusinessId,
        String classOfBusinessName,
        String customerName,
        LocalDate policyStartDate,
        LocalDate policyEndDate,
        BigDecimal totalSumInsured,
        BigDecimal netPremium,
        String currencyCode,
        String approvedBy,
        LocalDate approvedDate,
        String notes
) {}
