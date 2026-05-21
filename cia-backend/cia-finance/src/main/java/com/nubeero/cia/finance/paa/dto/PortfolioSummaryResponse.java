package com.nubeero.cia.finance.paa.dto;

import java.util.UUID;

/**
 * Read model for one IFRS 17 portfolio. Returned by
 * {@code GET /api/v1/finance/paa/portfolios}. Portfolios are master data
 * populated on first need per tenant by {@code ContractGroupingService};
 * this DTO is the wire representation for the frontend filter dropdown +
 * any listing UX.
 *
 * <p>Module 12 Phase 2 / Phase 5 frontend slice F5.11.
 */
public record PortfolioSummaryResponse(
    UUID id,
    String code,
    String name,
    UUID classOfBusinessId,
    String description,
    boolean active
) {}
