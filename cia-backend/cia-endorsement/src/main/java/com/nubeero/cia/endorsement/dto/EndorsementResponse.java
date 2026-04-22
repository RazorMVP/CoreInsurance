package com.nubeero.cia.endorsement.dto;

import com.nubeero.cia.endorsement.EndorsementStatus;
import com.nubeero.cia.endorsement.EndorsementType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record EndorsementResponse(
        UUID id,
        String endorsementNumber,
        EndorsementStatus status,
        EndorsementType endorsementType,
        UUID policyId,
        String policyNumber,
        UUID customerId,
        String customerName,
        String productName,
        String classOfBusinessName,
        UUID brokerId,
        String brokerName,
        LocalDate effectiveDate,
        LocalDate policyEndDate,
        int remainingDays,
        BigDecimal oldSumInsured,
        BigDecimal newSumInsured,
        BigDecimal oldNetPremium,
        BigDecimal newNetPremium,
        BigDecimal premiumAdjustment,
        String currencyCode,
        String description,
        String notes,
        String approvedBy,
        Instant approvedAt,
        String rejectedBy,
        Instant rejectedAt,
        String rejectionReason,
        Instant createdAt,
        List<EndorsementRiskResponse> risks
) {}
