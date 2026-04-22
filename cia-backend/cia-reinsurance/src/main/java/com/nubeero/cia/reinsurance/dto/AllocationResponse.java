package com.nubeero.cia.reinsurance.dto;

import com.nubeero.cia.reinsurance.AllocationStatus;
import com.nubeero.cia.reinsurance.TreatyType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AllocationResponse(
        UUID id,
        String allocationNumber,
        UUID policyId,
        String policyNumber,
        UUID endorsementId,
        UUID treatyId,
        TreatyType treatyType,
        AllocationStatus status,
        BigDecimal ourShareSumInsured,
        BigDecimal retainedAmount,
        BigDecimal cededAmount,
        BigDecimal excessAmount,
        BigDecimal ourSharePremium,
        BigDecimal retainedPremium,
        BigDecimal cededPremium,
        String currencyCode,
        List<AllocationLineResponse> lines,
        Instant createdAt
) {}
