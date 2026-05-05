package com.nubeero.cia.claims.dto;

import java.math.BigDecimal;

public record UpdateClaimRequest(
        String lossLocation,
        String natureOfLoss,
        String causeOfLoss,
        String contactName,
        String contactPhone,
        String description,
        BigDecimal estimatedLoss,
        String notes
) {}
