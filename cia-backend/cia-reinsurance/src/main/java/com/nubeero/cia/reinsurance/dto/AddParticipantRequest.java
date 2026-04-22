package com.nubeero.cia.reinsurance.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record AddParticipantRequest(
        @NotNull UUID reinsuranceCompanyId,
        @NotNull @DecimalMin("0.01") @DecimalMax("100.00") BigDecimal sharePercentage,
        Integer surplusLine,
        Boolean lead,
        @DecimalMin("0") @DecimalMax("100") BigDecimal commissionRate
) {}
