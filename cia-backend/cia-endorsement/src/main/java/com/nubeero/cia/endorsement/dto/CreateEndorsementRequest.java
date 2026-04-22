package com.nubeero.cia.endorsement.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateEndorsementRequest(
        @NotNull UUID policyId,
        @NotNull LocalDate effectiveDate,
        @NotBlank String description,
        String notes,
        BigDecimal newSumInsured,
        BigDecimal newNetPremium,
        @Valid List<EndorsementRiskRequest> risks
) {}
