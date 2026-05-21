package com.nubeero.cia.finance.paa.dto;

import com.nubeero.cia.finance.paa.GroupStatus;
import com.nubeero.cia.finance.paa.Onerousness;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for one IFRS 17 group of contracts. Returned by
 * {@code GET /api/v1/finance/paa/contract-groups}.
 *
 * <p>Portfolio fields are denormalised inline so the browser DataTable
 * doesn't need a follow-up lookup. Slice 2.2's {@code ContractGroupingService}
 * is the only writer of {@code group_of_contracts}; this DTO is read-only
 * and never round-trips back to the server.
 *
 * <p>Module 12 Phase 2 / Phase 5 frontend slice F5.11.
 */
public record ContractGroupSummaryResponse(
    UUID id,
    UUID portfolioId,
    String portfolioCode,
    String portfolioName,
    Integer cohortYear,
    Onerousness onerousness,
    GroupStatus status,
    Instant createdAt
) {}
