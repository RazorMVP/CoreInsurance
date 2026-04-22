package com.nubeero.cia.reinsurance.dto;

import com.nubeero.cia.reinsurance.FacCoverStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FacCoverResponse(
        UUID id,
        String facReference,
        UUID policyId,
        String policyNumber,
        UUID reinsuranceCompanyId,
        String reinsuranceCompanyName,
        FacCoverStatus status,
        BigDecimal sumInsuredCeded,
        BigDecimal premiumRate,
        BigDecimal premiumCeded,
        BigDecimal commissionRate,
        BigDecimal commissionAmount,
        BigDecimal netPremium,
        String currencyCode,
        LocalDate coverFrom,
        LocalDate coverTo,
        String offerSlipReference,
        String terms,
        String approvedBy,
        Instant approvedAt,
        String cancelledBy,
        Instant cancelledAt,
        String cancellationReason,
        Instant createdAt
) {}
