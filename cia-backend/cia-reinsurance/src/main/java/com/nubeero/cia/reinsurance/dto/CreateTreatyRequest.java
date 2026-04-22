package com.nubeero.cia.reinsurance.dto;

import com.nubeero.cia.reinsurance.TreatyType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateTreatyRequest(
        @NotNull TreatyType treatyType,
        @NotNull @Positive int treatyYear,
        UUID productId,
        UUID classOfBusinessId,
        BigDecimal retentionLimit,
        BigDecimal surplusCapacity,
        BigDecimal xolPerRiskRetention,
        BigDecimal xolPerRiskLimit,
        String currencyCode,
        @NotNull LocalDate effectiveDate,
        @NotNull LocalDate expiryDate,
        String description
) {}
