package com.nubeero.cia.claims.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ClaimReserveResponse(
        UUID id,
        BigDecimal amount,
        BigDecimal previousAmount,
        String reason,
        String createdBy,
        Instant createdAt
) {}
