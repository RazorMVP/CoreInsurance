package com.nubeero.cia.documents;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record EndorsementDocumentContext(
        UUID endorsementId,
        String endorsementNumber,
        UUID productId,
        UUID classOfBusinessId,
        String policyNumber,
        String customerName,
        String endorsementType,
        LocalDate effectiveDate,
        LocalDate policyEndDate,
        BigDecimal oldSumInsured,
        BigDecimal newSumInsured,
        BigDecimal premiumAdjustment,
        String currencyCode,
        String description,
        String approvedBy,
        LocalDate approvedDate
) {}
