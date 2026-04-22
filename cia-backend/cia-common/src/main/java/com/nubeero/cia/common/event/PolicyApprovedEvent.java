package com.nubeero.cia.common.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PolicyApprovedEvent(
        UUID policyId,
        String policyNumber,
        UUID customerId,
        String customerName,
        UUID brokerId,
        String brokerName,
        String productName,
        BigDecimal netPremium,
        String currencyCode,
        LocalDate policyEndDate,
        // RI allocation fields
        UUID productId,
        UUID classOfBusinessId,
        BigDecimal totalSumInsured,
        LocalDate policyStartDate
) {}
