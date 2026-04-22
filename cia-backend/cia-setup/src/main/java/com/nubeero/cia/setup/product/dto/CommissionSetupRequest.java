package com.nubeero.cia.setup.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CommissionSetupRequest {

    @NotBlank
    private String brokerType;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal rate;

    @NotNull
    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;
}
