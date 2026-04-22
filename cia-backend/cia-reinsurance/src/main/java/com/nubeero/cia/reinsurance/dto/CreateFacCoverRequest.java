package com.nubeero.cia.reinsurance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateFacCoverRequest(
        @NotNull UUID policyId,
        @NotBlank String policyNumber,
        @NotNull UUID reinsuranceCompanyId,
        @NotNull @DecimalMin("0.01") BigDecimal sumInsuredCeded,
        @NotNull @DecimalMin("0.000001") BigDecimal premiumRate,
        BigDecimal commissionRate,
        String currencyCode,
        @NotNull LocalDate coverFrom,
        @NotNull LocalDate coverTo,
        String offerSlipReference,
        String terms
) {}
