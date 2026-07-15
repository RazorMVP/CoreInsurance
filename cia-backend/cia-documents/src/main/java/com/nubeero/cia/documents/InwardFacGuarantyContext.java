package com.nubeero.cia.documents;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InwardFacGuarantyContext(
        UUID facInwardId,
        String facInwardReference,
        UUID classOfBusinessId,
        String cedingCompanyName,
        String classOfBusinessName,
        String riskDescription,
        BigDecimal sumInsured,
        BigDecimal ourSharePct,
        BigDecimal acceptedSumInsured,
        BigDecimal grossPremium,
        BigDecimal commissionAmount,
        BigDecimal netPremium,
        String currencyCode,
        LocalDate coverFrom,
        LocalDate coverTo
) {}
