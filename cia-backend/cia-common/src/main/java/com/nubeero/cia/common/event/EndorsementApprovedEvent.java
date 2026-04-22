package com.nubeero.cia.common.event;

import java.math.BigDecimal;
import java.util.UUID;

public record EndorsementApprovedEvent(
        UUID endorsementId,
        String endorsementNumber,
        UUID policyId,
        String policyNumber,
        UUID customerId,
        String customerName,
        UUID brokerId,
        String brokerName,
        String productName,
        BigDecimal premiumAdjustment,
        String currencyCode
) {}
