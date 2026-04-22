package com.nubeero.cia.reinsurance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TreatyParticipantResponse(
        UUID id,
        UUID reinsuranceCompanyId,
        String reinsuranceCompanyName,
        BigDecimal sharePercentage,
        Integer surplusLine,
        boolean lead,
        BigDecimal commissionRate,
        Instant createdAt
) {}
