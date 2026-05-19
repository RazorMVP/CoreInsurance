package com.nubeero.cia.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code ClaimService.markSettled} when a claim's DV is
 * disbursed. {@code settledAmount} is the actual {@code dvAmount} paid
 * (which may differ from the earlier {@code approvedAmount} carried on
 * {@code ClaimApprovedEvent} — the gap surfaces in the Slice 2.x LIC
 * roll-forward as a reserve true-up).
 *
 * <p>{@code settledAmount} was added in Slice 1.5 (Module 12 — Period-End
 * Closures) so {@code SubledgerPostingService} can post the settlement JE
 * (Dr 2140 LIC OCR, Cr 1120 Bank) without an extra DB lookup against
 * {@code cia-claims}.
 */
public record ClaimSettledEvent(
        UUID claimId,
        String claimNumber,
        UUID policyId,
        String policyNumber,
        UUID customerId,
        String customerName,
        BigDecimal settledAmount,
        String currencyCode,
        Instant settledAt
) {}
