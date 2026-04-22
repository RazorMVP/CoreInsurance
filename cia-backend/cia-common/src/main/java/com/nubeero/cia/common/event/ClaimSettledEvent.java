package com.nubeero.cia.common.event;

import java.time.Instant;
import java.util.UUID;

public record ClaimSettledEvent(
        UUID claimId,
        String claimNumber,
        UUID policyId,
        String policyNumber,
        UUID customerId,
        String customerName,
        Instant settledAt
) {}
