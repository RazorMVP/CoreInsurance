package com.nubeero.cia.finance.dto;

import com.nubeero.cia.finance.gl.FiscalYearStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read model for a fiscal year. {@code periods} is non-null only when the
 * caller explicitly opts in (e.g. embedding mode on
 * {@code GET /fiscal-years/{id}?includePeriods=true}); the unembedded list
 * endpoint leaves it null to keep payloads bounded.
 */
public record FiscalYearResponse(
    UUID id,
    String name,
    LocalDate startDate,
    LocalDate endDate,
    FiscalYearStatus status,
    Instant createdAt,
    List<FiscalPeriodResponse> periods
) {}
