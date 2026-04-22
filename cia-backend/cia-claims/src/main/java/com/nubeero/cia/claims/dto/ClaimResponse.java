package com.nubeero.cia.claims.dto;

import com.nubeero.cia.claims.ClaimStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ClaimResponse(
        UUID id,
        String claimNumber,
        ClaimStatus status,
        UUID policyId,
        String policyNumber,
        LocalDate policyStartDate,
        LocalDate policyEndDate,
        UUID customerId,
        String customerName,
        String productName,
        String classOfBusinessName,
        UUID brokerId,
        String brokerName,
        LocalDate incidentDate,
        LocalDate reportedDate,
        String lossLocation,
        String description,
        BigDecimal estimatedLoss,
        BigDecimal reserveAmount,
        BigDecimal approvedAmount,
        String currencyCode,
        UUID surveyorId,
        String surveyorName,
        Instant surveyorAssignedAt,
        String approvedBy,
        Instant approvedAt,
        String rejectedBy,
        Instant rejectedAt,
        String rejectionReason,
        String withdrawnBy,
        Instant withdrawnAt,
        String withdrawalReason,
        Instant settledAt,
        String notes,
        Instant createdAt
) {}
