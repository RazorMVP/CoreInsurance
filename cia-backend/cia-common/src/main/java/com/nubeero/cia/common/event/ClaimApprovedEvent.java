package com.nubeero.cia.common.event;

import java.math.BigDecimal;
import java.util.UUID;

public record ClaimApprovedEvent(
        UUID claimId,
        String claimNumber,
        UUID policyId,
        String policyNumber,
        UUID customerId,
        String customerName,
        UUID brokerId,
        String brokerName,
        String productName,
        BigDecimal approvedAmount,
        String currencyCode
) {}
