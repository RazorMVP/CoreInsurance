package com.nubeero.cia.claims.dto;

import java.math.BigDecimal;

public record UpdateClaimRequest(
        String lossLocation,
        String description,
        BigDecimal estimatedLoss,
        String notes
) {}
