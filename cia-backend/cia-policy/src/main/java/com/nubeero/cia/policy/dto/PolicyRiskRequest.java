package com.nubeero.cia.policy.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Data
public class PolicyRiskRequest {

    @NotBlank
    private String description;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal sumInsured;

    private UUID sectionId;
    private Map<String, Object> riskDetails;
    private String vehicleRegNumber;
}
