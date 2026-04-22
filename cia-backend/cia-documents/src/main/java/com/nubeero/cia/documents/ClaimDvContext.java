package com.nubeero.cia.documents;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ClaimDvContext(
        UUID claimId,
        String claimNumber,
        UUID productId,
        UUID classOfBusinessId,
        String policyNumber,
        String customerName,
        LocalDate incidentDate,
        String description,
        BigDecimal approvedAmount,
        String currencyCode,
        String approvedBy,
        LocalDate approvedDate
) {}
