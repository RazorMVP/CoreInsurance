package com.nubeero.cia.endorsement.dto;

import com.nubeero.cia.endorsement.EndorsementType;

import java.math.BigDecimal;

public record PremiumPreviewResponse(
        BigDecimal oldNetPremium,
        BigDecimal newNetPremium,
        long remainingDays,
        BigDecimal premiumAdjustment,
        EndorsementType endorsementType
) {}
