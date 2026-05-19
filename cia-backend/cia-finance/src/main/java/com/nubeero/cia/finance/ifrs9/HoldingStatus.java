package com.nubeero.cia.finance.ifrs9;

/**
 * Lifecycle status of an {@link InvestmentHolding}. Drives engine
 * eligibility — only ACTIVE holdings are measured by the period-end
 * engines (Slices 3.3-3.5).
 *
 * <p>Values match {@code ck_investment_status} in V39.
 */
public enum HoldingStatus {

    /** Held by the insurer, actively measured each period. */
    ACTIVE,

    /** Reached its maturity date and was settled at face value. Frozen final period after maturity. */
    MATURED,

    /** Disposed of before maturity. Carrying value gone to zero after sale settlement. */
    SOLD,

    /** Credit-impaired (Stage 3) or written down to zero. */
    IMPAIRED
}
