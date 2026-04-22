package com.nubeero.cia.setup.product.dto;

import com.nubeero.cia.setup.product.SurveyThresholdType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SurveyThresholdRequest {

    @NotNull
    private SurveyThresholdType thresholdType;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal minSumInsured;
}
