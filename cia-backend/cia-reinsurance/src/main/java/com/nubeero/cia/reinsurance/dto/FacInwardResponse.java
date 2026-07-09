package com.nubeero.cia.reinsurance.dto;

import com.nubeero.cia.reinsurance.RiFacInwardStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FacInwardResponse(
        UUID id,
        String facInwardReference,
        UUID cedingCompanyId,
        String cedingCompanyName,
        UUID classOfBusinessId,
        String classOfBusinessName,
        String riskDescription,
        BigDecimal sumInsured,
        BigDecimal ourSharePct,
        BigDecimal acceptedSumInsured,
        BigDecimal premiumRate,
        BigDecimal grossPremium,
        BigDecimal commissionRate,
        BigDecimal commissionAmount,
        BigDecimal netPremium,
        String currencyCode,
        LocalDate coverFrom,
        LocalDate coverTo,
        RiFacInwardStatus status,
        UUID renewedFromId,
        String guarantyDocumentPath,
        String cancelledBy,
        Instant cancelledAt,
        String cancellationReason,
        Instant createdAt
) {}
