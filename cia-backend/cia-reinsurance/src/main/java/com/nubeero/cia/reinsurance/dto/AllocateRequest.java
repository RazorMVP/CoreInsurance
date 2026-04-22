package com.nubeero.cia.reinsurance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record AllocateRequest(
        @NotNull UUID policyId,
        @NotBlank String policyNumber,
        @NotNull UUID treatyId,
        @NotNull @DecimalMin("0.01") BigDecimal sumInsured,
        @NotNull @DecimalMin("0.01") BigDecimal premium,
        String currencyCode,
        UUID endorsementId
) {}
