package com.nubeero.cia.reinsurance.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AllocationLineResponse(
        UUID id,
        UUID reinsuranceCompanyId,
        String reinsuranceCompanyName,
        BigDecimal sharePercentage,
        BigDecimal cededAmount,
        BigDecimal cededPremium,
        BigDecimal commissionRate,
        BigDecimal commissionAmount
) {}
