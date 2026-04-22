package com.nubeero.cia.quotation.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class QuoteCoinsuranceParticipantRequest {

    @NotNull
    private UUID insuranceCompanyId;

    @NotNull
    @DecimalMin("0.01")
    @DecimalMax("99.99")
    private BigDecimal sharePercentage;
}
