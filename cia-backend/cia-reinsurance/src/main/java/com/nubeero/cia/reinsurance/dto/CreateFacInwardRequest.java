package com.nubeero.cia.reinsurance.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateFacInwardRequest(
        @NotNull UUID cedingCompanyId,
        @NotNull UUID classOfBusinessId,
        String riskDescription,
        @NotNull @DecimalMin("0.01") BigDecimal sumInsured,
        @NotNull @DecimalMin("0.0001") @DecimalMax("100.0000") BigDecimal ourSharePct,
        @NotNull @DecimalMin("0.000001") BigDecimal premiumRate,
        BigDecimal commissionRate,
        String currencyCode,
        @NotNull LocalDate coverFrom,
        @NotNull LocalDate coverTo
) {}
