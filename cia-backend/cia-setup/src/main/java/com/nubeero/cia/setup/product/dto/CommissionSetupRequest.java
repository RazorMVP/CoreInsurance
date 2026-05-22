package com.nubeero.cia.setup.product.dto;

import com.nubeero.cia.setup.product.CommissionSourceType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CommissionSetupRequest {

    @NotNull
    private CommissionSourceType commissionSource;

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private BigDecimal rate;

    @NotNull
    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;
}
