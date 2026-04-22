package com.nubeero.cia.claims.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RegisterClaimRequest(
        @NotNull UUID policyId,
        @NotNull LocalDate incidentDate,
        @NotNull LocalDate reportedDate,
        String lossLocation,
        @NotBlank String description,
        BigDecimal estimatedLoss,
        String notes
) {}
