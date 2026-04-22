package com.nubeero.cia.common.event;

import java.math.BigDecimal;
import java.util.UUID;

public record FacPremiumCededEvent(
        UUID facCoverId,
        String facReference,
        UUID policyId,
        String policyNumber,
        UUID reinsuranceCompanyId,
        String reinsuranceCompanyName,
        BigDecimal premiumCeded,
        BigDecimal commissionAmount,
        BigDecimal netPremiumCeded,
        String currencyCode
) {}
