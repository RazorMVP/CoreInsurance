package com.nubeero.cia.finance.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Top-level response for {@code GET /api/v1/finance/trial-balance?asOf=...}.
 *
 * <p>D4=A — {@code asOf} is interpreted on {@code business_date}, so the
 * trial balance is cumulative since inception (every JE with
 * {@code business_date <= asOf} is included). {@code generatedAt} is the
 * server clock when the snapshot was assembled, recorded so consumers can
 * tell two requests apart and so the JSON evidence files (used by the
 * 100-JE reconciliation IT and by future tenant audit exports) carry a
 * deterministic provenance stamp.
 */
public record TrialBalanceResponse(
    LocalDate asOf,
    Instant generatedAt,
    List<TrialBalanceLine> lines,
    TrialBalanceFooter footer
) {}
