package com.nubeero.cia.finance.ifrs9;

/**
 * IFRS 9 §4.1.1 business model assessment for a debt instrument. Drives the
 * AC/FVOCI_DEBT/FVPL classification together with the SPPI test result.
 *
 * <p>Per §B4.1.1-§B4.1.6, the business model is determined at portfolio
 * level (not per individual instrument) — but for v1 we capture it
 * per-holding so admin actions are explicit and auditable. A future slice
 * may collapse repeated values into a portfolio-level setting.
 */
public enum BusinessModel {

    /** Objective is to hold the asset to collect contractual cashflows (§4.1.1(a)(i) + §B4.1.2-§B4.1.2C). */
    HOLD_TO_COLLECT,

    /** Objective is BOTH to collect contractual cashflows AND to sell (§4.1.1(b) + §B4.1.4-§B4.1.4C). */
    HOLD_TO_COLLECT_AND_SELL,

    /** Objective is to sell — neither held to collect nor a mix (§4.1.1(c) catch-all → FVPL). */
    SELL_FIRST
}
