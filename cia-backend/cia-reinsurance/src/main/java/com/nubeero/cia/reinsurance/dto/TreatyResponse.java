package com.nubeero.cia.reinsurance.dto;

import com.nubeero.cia.reinsurance.TreatyStatus;
import com.nubeero.cia.reinsurance.TreatyType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TreatyResponse(
        UUID id,
        TreatyType treatyType,
        TreatyStatus status,
        int treatyYear,
        UUID productId,
        UUID classOfBusinessId,
        BigDecimal retentionLimit,
        BigDecimal surplusCapacity,
        BigDecimal xolPerRiskRetention,
        BigDecimal xolPerRiskLimit,
        String currencyCode,
        LocalDate effectiveDate,
        LocalDate expiryDate,
        String description,
        List<TreatyParticipantResponse> participants,
        Instant createdAt
) {}
