package com.nubeero.cia.finance.ifrs9;

/**
 * Type of financial asset held. Drives SPPI eligibility and downstream
 * measurement behaviour:
 * <ul>
 *   <li>{@link #DEBT} — debt securities (corporate bonds, government
 *       securities). Eligible for AMORTISED_COST / FVOCI_DEBT / FVPL.</li>
 *   <li>{@link #EQUITY} — equity instruments. Default FVPL; FVOCI_EQUITY
 *       available via §5.7.5 irrevocable election.</li>
 *   <li>{@link #MONEY_MARKET} — short-term placements (T-bills, CP).
 *       Typically AMORTISED_COST when held to collect.</li>
 *   <li>{@link #DERIVATIVE} — derivatives. Always FVPL per §5.4.1(b).</li>
 * </ul>
 *
 * <p>Values match {@code ck_investment_asset_type} in V39.
 */
public enum AssetType {
    DEBT,
    EQUITY,
    MONEY_MARKET,
    DERIVATIVE
}
