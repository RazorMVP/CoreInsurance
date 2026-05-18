package com.nubeero.cia.finance.paa;

/**
 * Technique used to measure the risk adjustment for non-financial risk under
 * IFRS 17 §37. Recorded on {@link PaaConfig} as the tenant's accounting policy.
 *
 * <p>Values match {@code ck_paa_config_ra_method} in V36.
 */
public enum RiskAdjustmentMethod {

    /** Confidence-level (Value-at-Risk) approach — most common for short-tail GB. */
    CONFIDENCE_LEVEL,

    /** Cost-of-Capital approach — typical for longer-tail or capital-intensive lines. */
    COST_OF_CAPITAL,

    /** Scenario-based stress test — fallback for portfolios where statistical methods are unreliable. */
    STRESS_TEST
}
