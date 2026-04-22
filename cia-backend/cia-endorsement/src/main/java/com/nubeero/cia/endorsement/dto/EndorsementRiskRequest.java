package com.nubeero.cia.endorsement.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record EndorsementRiskRequest(
        @NotBlank String description,
        @NotNull @DecimalMin("0.00") BigDecimal sumInsured,
        @NotNull @DecimalMin("0.00") BigDecimal premium,
        UUID sectionId,
        String sectionName,
        Map<String, Object> riskDetails,
        String vehicleRegNumber
) {}
